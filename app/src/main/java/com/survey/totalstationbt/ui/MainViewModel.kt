package com.survey.totalstationbt.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.survey.totalstationbt.bluetooth.BluetoothScanner
import com.survey.totalstationbt.bluetooth.BluetoothSerialService
import com.survey.totalstationbt.model.BluetoothDeviceInfo
import com.survey.totalstationbt.model.ConnectionState
import com.survey.totalstationbt.model.SurveyPoint
import com.survey.totalstationbt.utils.FileExporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.io.File

import com.survey.totalstationbt.db.AppDatabase
import com.survey.totalstationbt.db.PointEntity
import com.survey.totalstationbt.db.ProjectEntity
import com.survey.totalstationbt.db.SurveyRepository
import com.survey.totalstationbt.model.DxfData
import com.survey.totalstationbt.model.DxfTransform
import com.survey.totalstationbt.parser.DxfParser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
@SuppressLint("MissingPermission")
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Context = app.applicationContext
    val btService = BluetoothSerialService()

    // Database
    private val database = AppDatabase.getDatabase(app)
    val repository = SurveyRepository(database.surveyDao())

    // ── 專案管理 ─────────────────────────────────
    private val _currentProject = MutableStateFlow<ProjectEntity?>(null)
    val currentProject: StateFlow<ProjectEntity?> = _currentProject.asStateFlow()

    val currentPoints: StateFlow<List<PointEntity>> = _currentProject
        .flatMapLatest { project ->
            if (project != null) repository.getPointsForProject(project.id)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        // 啟動時建立一個預設專案或加載最後一個專案
        viewModelScope.launch {
            repository.allProjects.firstOrNull()?.firstOrNull()?.let {
                _currentProject.value = it
                loadDxfForProject(it)
            } ?: run {
                val defaultProject = ProjectEntity(name = "預設專案 ${java.text.SimpleDateFormat("MMdd", java.util.Locale.getDefault()).format(java.util.Date())}")
                val id = repository.insertProject(defaultProject)
                val project = defaultProject.copy(id = id)
                _currentProject.value = project
            }
        }
    }

    // ── 解析與存儲 ──────────────────────────────
    init {
        // 監聽藍牙接收，並自動存入資料庫
        btService.receivedLines.onEach { lines ->
            val last = lines.lastOrNull() ?: return@onEach
            val project = _currentProject.value ?: return@onEach
            
            // 如果是剛接收到的（時間戳很接近現在），則存入 DB
            if (System.currentTimeMillis() - last.timestamp < 1000) {
                repository.insertPoint(PointEntity(
                    projectId = project.id,
                    pointName = last.parsed?.pointName ?: "Unknown",
                    easting = last.parsed?.easting,
                    northing = last.parsed?.northing,
                    elevation = last.parsed?.elevation,
                    code = if (last.parsed?.code?.isNotEmpty() == true) last.parsed.code else currentCode,
                    horizontalAngle = last.parsed?.horizontalAngle,
                    verticalAngle = last.parsed?.verticalAngle,
                    slopeDistance = last.parsed?.slopeDistance,
                    horizontalDistance = last.parsed?.horizontalDistance,
                    verticalDistance = last.parsed?.verticalDistance,
                    rawData = last.raw,
                    format = last.parsed?.format?.name ?: "UNKNOWN"
                ))
            }
        }.launchIn(viewModelScope)
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    // ── 連線狀態 ─────────────────────────────────
    val connectionState = btService.connectionState

    // ── 所有接收的原始行 ─────────────────────────
    val receivedLines = btService.receivedLines

    // ── 解析成功的測量點 ─────────────────────────
    val surveyPoints: StateFlow<List<SurveyPoint>> = receivedLines
        .map { lines -> lines.mapNotNull { it.parsed } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── 統計 ─────────────────────────────────────
    val stats: StateFlow<Stats> = currentPoints.map { points ->
        val parsed = points.filter { it.northing != null && it.easting != null }
        Stats(
            totalLines  = points.size,
            parsedCount = parsed.size,
            failedCount = points.size - parsed.size,
            lastPoint   = parsed.lastOrNull()?.let {
                com.survey.totalstationbt.model.SurveyPoint(
                    pointName = it.pointName,
                    northing = it.northing,
                    easting = it.easting,
                    elevation = it.elevation
                )
            }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Stats())

    data class Stats(
        val totalLines: Int = 0,
        val parsedCount: Int = 0,
        val failedCount: Int = 0,
        val lastPoint: SurveyPoint? = null
    )

    // ── 配對裝置清單 ─────────────────────────────
    private val _pairedDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDeviceInfo>> = _pairedDevices.asStateFlow()

    val allProjects = repository.allProjects.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── 匯出訊息 ─────────────────────────────────
    private val _exportEvent = MutableSharedFlow<ExportResult>()
    val exportEvent: SharedFlow<ExportResult> = _exportEvent.asSharedFlow()

    // ── 目前施測編碼 ─────────────────────────────
    var currentCode: String = ""

    // ── DXF 底圖快取 (跨 Activity 共享) ──────────
    companion object {
        var cachedDxfData: com.survey.totalstationbt.model.DxfData? = null
        var cachedDxfTransform: com.survey.totalstationbt.model.DxfTransform = com.survey.totalstationbt.model.DxfTransform.IDENTITY
    }

    private val _dxfLoadedEvent = MutableSharedFlow<Unit>(replay = 1)
    val dxfLoadedEvent = _dxfLoadedEvent.asSharedFlow()

    sealed class ExportResult {
        data class Success(val file: File) : ExportResult()
        data class Info(val message: String) : ExportResult()
        data class Error(val message: String) : ExportResult()
    }

    // ── Actions ───────────────────────────────────
    fun refreshPairedDevices() {
        val adapter = bluetoothAdapter ?: return
        _pairedDevices.value = BluetoothScanner.getPairedDevices(adapter)
    }

    fun connectDevice(deviceInfo: BluetoothDeviceInfo) {
        val adapter = bluetoothAdapter ?: return
        val device = try {
            adapter.getRemoteDevice(deviceInfo.address)
        } catch (e: IllegalArgumentException) {
            viewModelScope.launch { _exportEvent.emit(ExportResult.Error("裝置位址無效：${deviceInfo.address}")) }
            return
        }
        btService.connect(device, adapter)
    }

    fun disconnect() = btService.disconnect()

    fun clearData() {
        viewModelScope.launch {
            _currentProject.value?.let {
                repository.clearPointsForProject(it.id)
            }
        }
    }

    fun deletePoints(lines: List<BluetoothSerialService.ReceivedLine>) {
        viewModelScope.launch {
            lines.forEach { line ->
                // Find PointEntity by timestamp (unique enough for this)
                currentPoints.value.find { it.timestamp == line.timestamp }?.let {
                    repository.deletePoint(it)
                }
            }
        }
    }

    fun sendCommand(cmd: String) = btService.sendCommand(cmd)

    fun exportCSV() {
        viewModelScope.launch {
            val points = currentPoints.value.map {
                com.survey.totalstationbt.model.SurveyPoint(
                    pointName = it.pointName,
                    easting = it.easting,
                    northing = it.northing,
                    elevation = it.elevation,
                    code = it.code,
                    timestamp = it.timestamp
                )
            }
            if (points.isEmpty()) {
                _exportEvent.emit(ExportResult.Error("沒有可匯出的資料"))
                return@launch
            }
            try {
                val file = FileExporter.exportCSV(context, points)
                _exportEvent.emit(ExportResult.Success(file))
            } catch (e: Exception) {
                _exportEvent.emit(ExportResult.Error("匯出失敗：${e.message}"))
            }
        }
    }

    fun exportRaw() {
        viewModelScope.launch {
            val lines = currentPoints.value.map { it.rawData }
            if (lines.isEmpty()) {
                _exportEvent.emit(ExportResult.Error("沒有可匯出的資料"))
                return@launch
            }
            try {
                val file = FileExporter.exportRaw(context, lines)
                _exportEvent.emit(ExportResult.Success(file))
            } catch (e: Exception) {
                _exportEvent.emit(ExportResult.Error("匯出失敗：${e.message}"))
            }
        }
    }

    fun exportDXF() {
        viewModelScope.launch {
            val points = currentPoints.value
            if (points.isEmpty()) {
                _exportEvent.emit(ExportResult.Error("沒有可匯出的資料"))
                return@launch
            }
            try {
                val file = FileExporter.exportDXF(context, points)
                _exportEvent.emit(ExportResult.Success(file))
            } catch (e: Exception) {
                _exportEvent.emit(ExportResult.Error("匯出失敗：${e.message}"))
            }
        }
    }

    fun shareFile(file: File) = FileExporter.shareFile(context, file)

    fun setCurrentProject(project: ProjectEntity) {
        _currentProject.value = project
        // 當切換專案時，若有持久化的 DXF 資訊則嘗試載入
        loadDxfForProject(project)
    }

    private fun loadDxfForProject(project: ProjectEntity) {
        val path = project.dxfPath ?: return
        val file = File(path)
        if (!file.exists()) return
        
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                try {
                    file.inputStream().use { DxfParser.parse(it) }
                } catch (e: Exception) {
                    null
                }
            }
            if (data != null) {
                cachedDxfData = data
                cachedDxfTransform = DxfTransform(
                    project.dxfDx, project.dxfDy, 
                    project.dxfScale, project.dxfRotation
                )
                _dxfLoadedEvent.emit(Unit)
            }
        }
    }

    fun saveDxfToProject(uri: android.net.Uri, transform: DxfTransform) {
        val project = _currentProject.value ?: return
        viewModelScope.launch {
            val savedPath = withContext(Dispatchers.IO) {
                try {
                    val dxfFolder = File(context.filesDir, "cad_maps")
                    if (!dxfFolder.exists()) dxfFolder.mkdirs()
                    
                    val fileName = "project_${project.id}_base.dxf"
                    val targetFile = File(dxfFolder, fileName)
                    
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    targetFile.absolutePath
                } catch (e: Exception) {
                    null
                }
            }
            
            if (savedPath != null) {
                val updatedProject = project.copy(
                    dxfPath = savedPath,
                    dxfDx = transform.dx,
                    dxfDy = transform.dy,
                    dxfScale = transform.scale,
                    dxfRotation = transform.rotationRad
                )
                repository.updateProject(updatedProject)
                _currentProject.value = updatedProject
                cachedDxfTransform = transform
            }
        }
    }

    fun updateDxfTransform(transform: DxfTransform) {
        val project = _currentProject.value ?: return
        viewModelScope.launch {
            val updatedProject = project.copy(
                dxfDx = transform.dx,
                dxfDy = transform.dy,
                dxfScale = transform.scale,
                dxfRotation = transform.rotationRad
            )
            repository.updateProject(updatedProject)
            _currentProject.value = updatedProject
            cachedDxfTransform = transform
        }
    }

    fun deleteProject(project: ProjectEntity) {
        viewModelScope.launch {
            if (_currentProject.value?.id == project.id) {
                _currentProject.value = null
            }
            repository.deleteProject(project)
            
            // 如果刪除後沒專案了，建立一個新的
            val projects = repository.allProjects.first()
            if (projects.isEmpty()) {
                val defaultProject = ProjectEntity(name = "預設專案 ${java.text.SimpleDateFormat("MMdd", java.util.Locale.getDefault()).format(java.util.Date())}")
                val id = repository.insertProject(defaultProject)
                _currentProject.value = defaultProject.copy(id = id)
            } else if (_currentProject.value == null) {
                _currentProject.value = projects[0]
            }
        }
    }

    fun createProject(name: String) {
        viewModelScope.launch {
            val newProject = ProjectEntity(name = name)
            val id = repository.insertProject(newProject)
            _currentProject.value = newProject.copy(id = id)
        }
    }

    fun syncToGoogleDrive() {
        viewModelScope.launch {
            _exportEvent.emit(ExportResult.Error("Google Drive 同步功能需要配置 API Key。目前已將資料備份至本地。"))
            exportCSV()
        }
    }

    fun updatePointPhoto(pointId: Long, path: String) {
        viewModelScope.launch {
            val points = currentPoints.value
            points.find { it.id == pointId }?.let {
                repository.updatePoint(it.copy(photoPath = path))
            }
        }
    }

    fun updatePointNote(pointId: Long, note: String) {
        viewModelScope.launch { repository.updatePointNote(pointId, note) }
    }

    fun updatePointCode(pointId: Long, code: String) {
        viewModelScope.launch { repository.updatePointCode(pointId, code) }
    }

    fun uploadToNikon() {
        viewModelScope.launch {
            val points = currentPoints.value
            if (points.isEmpty()) {
                _exportEvent.emit(ExportResult.Error("沒有可上傳的點位"))
                return@launch
            }

            _exportEvent.emit(ExportResult.Info("開始傳輸 ${points.size} 個點位…"))

            points.forEachIndexed { index, pt ->
                // Nikon 格式: PT,N,E,Z,CD
                val line = buildString {
                    append(pt.pointName.take(16)) // 通常限制 16 字元
                    append(",")
                    append(String.format("%.3f", pt.northing ?: 0.0))
                    append(",")
                    append(String.format("%.3f", pt.easting ?: 0.0))
                    append(",")
                    append(String.format("%.3f", pt.elevation ?: 0.0))
                    append(",")
                    append("STAKE") // 預設 Code
                }
                
                btService.sendCommand(line)
                
                // 每傳一個點位稍微延遲，避免全站儀緩衝溢位 (Serial 傳輸較慢)
                delay(100) 
            }
            
            _exportEvent.emit(ExportResult.Info("整批點位傳輸完成！"))
        }
    }

    fun importData(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val project = _currentProject.value ?: return@launch
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch
                val lines = inputStream.bufferedReader().readLines()
                inputStream.close()

                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        val parsed = com.survey.totalstationbt.parser.TotalStationParser.parse(line)
                        repository.insertPoint(PointEntity(
                            projectId = project.id,
                            pointName = parsed?.pointName ?: "Imported",
                            easting = parsed?.easting,
                            northing = parsed?.northing,
                            elevation = parsed?.elevation,
                            code = parsed?.code ?: "",
                            horizontalAngle = parsed?.horizontalAngle,
                            verticalAngle = parsed?.verticalAngle,
                            slopeDistance = parsed?.slopeDistance,
                            horizontalDistance = parsed?.horizontalDistance,
                            verticalDistance = parsed?.verticalDistance,
                            rawData = line,
                            format = parsed?.format?.name ?: "UNKNOWN"
                        ))
                    }
                }
                _exportEvent.emit(ExportResult.Info("匯入完成 (${lines.size} 行)"))
            } catch (e: Exception) {
                _exportEvent.emit(ExportResult.Error("匯入失敗：${e.message}"))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        btService.destroy()
    }
}
