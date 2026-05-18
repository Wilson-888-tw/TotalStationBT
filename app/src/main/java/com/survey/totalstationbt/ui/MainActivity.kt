package com.survey.totalstationbt.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.view.MenuCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.survey.totalstationbt.R
import com.survey.totalstationbt.databinding.ActivityMainBinding
import com.survey.totalstationbt.model.BluetoothDeviceInfo
import com.survey.totalstationbt.model.ConnectionState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.core.content.FileProvider
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var dataAdapter: DataAdapter
    private var currentPhotoPath: String? = null
    private var photoPointId: Long? = null

    // ── 權限請求 ─────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.refreshPairedDevices()
            showDeviceDialog()
        } else {
            showSnackbar("需要藍牙權限才能使用此功能")
        }
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkPermissionsAndScan()
        }
    }

    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val pointId = photoPointId ?: return@registerForActivityResult
            val path = currentPhotoPath ?: return@registerForActivityResult
            viewModel.updatePointPhoto(pointId, path)
            showSnackbar("相片已關聯至點位")
        }
    }

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importData(it) }
    }

    // ── onCreate ─────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupButtons()
        setupCodeChips()
        observeViewModel()
    }

    // ── RecyclerView ─────────────────────────────
    private fun setupRecyclerView() {
        dataAdapter = DataAdapter { action ->
            when (action) {
                is DataAdapter.Action.Photo -> startCamera(action.line)
                is DataAdapter.Action.Share -> sharePoint(action.line)
                is DataAdapter.Action.Edit -> showEditCodeDialog(action.line)
                is DataAdapter.Action.SelectionChanged -> {
                    if (action.count > 0) {
                        binding.btnDeleteSelected.visibility = android.view.View.VISIBLE
                        binding.btnDeleteSelected.text = "移除選取(${action.count})"
                    } else {
                        binding.btnDeleteSelected.visibility = android.view.View.GONE
                    }
                }
            }
        }
        binding.recyclerData.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).also {
                it.stackFromEnd = true
            }
            adapter = dataAdapter
            itemAnimator = null
        }
    }

    // ── 按鈕事件 ─────────────────────────────────
    private fun setupButtons() {
        binding.btnDeleteSelected.setOnClickListener {
            val selected = dataAdapter.getSelectedLines()
            if (selected.isEmpty()) return@setOnClickListener
            
            MaterialAlertDialogBuilder(this)
                .setTitle("確認移除")
                .setMessage("確定要移除選取的 ${selected.size} 筆點位嗎？")
                .setPositiveButton("確認移除") { _, _ ->
                    viewModel.deletePoints(selected)
                    dataAdapter.clearSelection()
                    showSnackbar("已移除選取點位")
                }
                .setNegativeButton("取消", null)
                .show()
        }
        binding.editCurrentCode.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.currentCode = s?.toString()?.trim() ?: ""
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.btnImport.setOnClickListener {
            importFileLauncher.launch("*/*")
        }

        binding.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("清除資料")
                .setMessage("確定要清除所有接收的資料嗎？")
                .setPositiveButton("清除") { _, _ -> viewModel.clearData() }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.btnExportCsv.setOnClickListener { viewModel.exportCSV() }

        binding.editCurrentCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                val code = binding.editCurrentCode.text?.toString()?.trim()?.uppercase() ?: ""
                if (code.isNotEmpty()) addCodeChip(code, save = true)
            }
            false
        }
    }

    // ── 快速編碼 Chip ─────────────────────────────
    private fun setupCodeChips() {
        val prefs = getSharedPreferences("chip_codes", Context.MODE_PRIVATE)
        val defaultCodes = listOf("TREE", "BM", "MH", "EP", "GR")
        val saved = prefs.getStringSet("codes", null)
        val codes = if (saved.isNullOrEmpty()) defaultCodes else saved.toList().sorted()
        codes.forEach { addCodeChip(it, save = false) }
    }

    private fun addCodeChip(code: String, save: Boolean) {
        val group = binding.chipGroupCodes
        if ((0 until group.childCount).any { (group.getChildAt(it) as? Chip)?.text == code }) return

        val chip = Chip(this).apply {
            text = code
            isCheckable = false
            setOnClickListener {
                binding.editCurrentCode.setText(code)
                viewModel.currentCode = code
            }
            setOnLongClickListener {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setMessage("移除快速碼「$code」？")
                    .setPositiveButton("移除") { _, _ ->
                        group.removeView(this)
                        saveChipCodes()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }
        group.addView(chip)
        if (save) saveChipCodes()
    }

    private fun saveChipCodes() {
        val group = binding.chipGroupCodes
        val codes = (0 until group.childCount).mapNotNull { (group.getChildAt(it) as? Chip)?.text?.toString() }.toSet()
        getSharedPreferences("chip_codes", Context.MODE_PRIVATE).edit().putStringSet("codes", codes).apply()
    }

    // ── 觀察 ViewModel ────────────────────────────
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.connectionState.collectLatest { state ->
                updateConnectionUI(state)
            }
        }

        lifecycleScope.launch {
            viewModel.currentProject.collectLatest { project ->
                binding.toolbar.title = project?.name ?: "全站儀 BT 接收器"
            }
        }

        lifecycleScope.launch {
            viewModel.currentPoints.collectLatest { points ->
                // 將 DB 的 PointEntity 轉回 DataAdapter 需要的格式（如果是共用同一個 Adapter）
                // 或是更新 DataAdapter 以支援 PointEntity
                dataAdapter.submitList(points.map { 
                    com.survey.totalstationbt.bluetooth.BluetoothSerialService.ReceivedLine(
                        raw = it.rawData,
                        parsed = com.survey.totalstationbt.model.SurveyPoint(
                            pointName = it.pointName,
                            northing = it.northing,
                            easting = it.easting,
                            elevation = it.elevation,
                            horizontalAngle = it.horizontalAngle,
                            verticalAngle = it.verticalAngle,
                            slopeDistance = it.slopeDistance,
                            horizontalDistance = it.horizontalDistance,
                            verticalDistance = it.verticalDistance,
                            rawData = it.rawData
                        ),
                        timestamp = it.timestamp
                    )
                })
                if (points.isNotEmpty()) {
                    binding.recyclerData.smoothScrollToPosition(points.size - 1)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.stats.collectLatest { stats ->
                binding.tvStatTotal.text  = "${stats.totalLines}"
                binding.tvStatParsed.text = "${stats.parsedCount}"
                binding.tvStatFailed.text = "${stats.failedCount}"
                stats.lastPoint?.let { pt ->
                    binding.tvLastPoint.text = buildString {
                        append("最新：${pt.pointName.ifEmpty { "-" }}")
                        pt.northing?.let { append("  N=${String.format("%.3f", it)}") }
                        pt.easting?.let  { append("  E=${String.format("%.3f", it)}") }
                        pt.elevation?.let { append("  H=${String.format("%.3f", it)}") }
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.exportEvent.collectLatest { result ->
                when (result) {
                    is MainViewModel.ExportResult.Success -> {
                        Snackbar.make(
                            binding.root,
                            "已匯出：${result.file.name}",
                            Snackbar.LENGTH_LONG
                        ).setAction("分享") {
                            viewModel.shareFile(result.file)
                        }.show()
                    }
                    is MainViewModel.ExportResult.Error -> {
                        showSnackbar(result.message)
                    }
                }
            }
        }
    }

    // ── 更新連線 UI ───────────────────────────────
    private fun updateConnectionUI(state: ConnectionState) {
        invalidateOptionsMenu()
        val (statusText, colorRes) = when (state) {
            is ConnectionState.Disconnected -> "● 未連線" to R.color.status_disconnected
            is ConnectionState.Connecting   -> "⟳ 連線中：${state.deviceName}" to R.color.status_connecting
            is ConnectionState.Connected    -> "● 已連線：${state.deviceName}" to R.color.status_connected
            is ConnectionState.Error        -> "✕ ${state.message}" to R.color.status_error
        }
        val color = getColor(colorRes)
        binding.tvStatus.text = statusText
        binding.tvStatus.setTextColor(color)
        binding.viewStatusBar.setBackgroundColor(color)
    }

    // ── 裝置選擇 Dialog ───────────────────────────
    private fun showDeviceDialog() {
        val devices = viewModel.pairedDevices.value
        if (devices.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("找不到配對裝置")
                .setMessage("請先在系統設定中配對您的全站儀藍牙裝置。")
                .setPositiveButton("前往設定") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val names = devices.map { "${it.name}\n${it.address}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("選擇全站儀")
            .setItems(names) { _, index ->
                connectDevice(devices[index])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun connectDevice(device: BluetoothDeviceInfo) {
        viewModel.connectDevice(device)
        showSnackbar("正在連線 ${device.name}…")
    }

    private fun showEditCodeDialog(line: com.survey.totalstationbt.bluetooth.BluetoothSerialService.ReceivedLine) {
        val point = viewModel.currentPoints.value.find { it.timestamp == line.timestamp } ?: return
        val input = android.widget.EditText(this)
        input.setText(point.code)
        input.hint = "輸入施測編碼"
        input.setSelection(input.text.length)

        MaterialAlertDialogBuilder(this)
            .setTitle("修改施測編碼")
            .setMessage("點號：${point.pointName}")
            .setView(input)
            .setPositiveButton("儲存") { _, _ ->
                val newCode = input.text.toString().trim().uppercase()
                viewModel.updatePointCode(point.id, newCode)
                showSnackbar("編碼已更新")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDisconnectDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("中斷連線")
            .setMessage("確定要中斷藍牙連線嗎？")
            .setPositiveButton("中斷") { _, _ -> viewModel.disconnect() }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── 權限處理 ─────────────────────────────────
    private fun checkPermissionsAndScan() {
        val adapter = viewModel.bluetoothAdapter
        if (adapter == null) {
            showSnackbar("此裝置不支援藍牙")
            return
        }
        if (!adapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 除了 SCAN 和 CONNECT，部分裝置仍可能需要 LOCATION
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            viewModel.refreshPairedDevices()
            showDeviceDialog()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ── Menu ─────────────────────────────────────
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        MenuCompat.setGroupDividerEnabled(menu, true)

        val connectItem = menu.findItem(R.id.menu_connect)
        when (viewModel.connectionState.value) {
            is ConnectionState.Connected -> connectItem.setIcon(R.drawable.ic_bluetooth_connected)
            else -> connectItem.setIcon(R.drawable.ic_bluetooth_search)
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_connect -> {
                when (viewModel.connectionState.value) {
                    is ConnectionState.Connected -> showDisconnectDialog()
                    else -> checkPermissionsAndScan()
                }
                true
            }
            R.id.menu_map -> {
                startActivity(Intent(this, MapActivity::class.java))
                true
            }
            R.id.menu_projects -> {
                showProjectDialog()
                true
            }
            R.id.menu_upload_nikon -> {
                showNikonUploadConfirmDialog()
                true
            }
            R.id.menu_export_csv -> { viewModel.exportCSV(); true }
            R.id.menu_export_dxf -> { viewModel.exportDXF(); true }
            R.id.menu_export_raw -> { viewModel.exportRaw(); true }
            R.id.menu_export_raw -> { viewModel.exportRaw(); true }
            R.id.menu_clear      -> { viewModel.clearData(); true }
            R.id.menu_about      -> { showAboutDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
        } catch (e: Exception) { "—" }
        view.findViewById<TextView>(R.id.tvAboutVersion)?.text = "v$versionName"

        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton("確定", null)
            .show()
    }

    private fun showProjectDialog() {
        val projects = viewModel.allProjects.value
        val names = mutableListOf("新建專案…")
        names.addAll(projects.map { "${it.name} (${java.text.SimpleDateFormat("MM/dd", java.util.Locale.getDefault()).format(it.date)})" })

        MaterialAlertDialogBuilder(this)
            .setTitle("管理專案 (長按可刪除)")
            .setItems(names.toTypedArray()) { _, index ->
                if (index == 0) {
                    showNewProjectDialog()
                } else {
                    viewModel.setCurrentProject(projects[index - 1])
                    showSnackbar("已切換專案：${projects[index - 1].name}")
                }
            }
            .setNeutralButton("刪除專案") { _, _ ->
                showDeleteProjectDialog()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteProjectDialog() {
        val projects = viewModel.allProjects.value
        val names = projects.map { it.name }.toTypedArray()
        
        MaterialAlertDialogBuilder(this)
            .setTitle("選擇要刪除的專案")
            .setItems(names) { _, index ->
                val project = projects[index]
                MaterialAlertDialogBuilder(this)
                    .setTitle("確認刪除")
                    .setMessage("確定要刪除「${project.name}」嗎？這將會清空該專案下的所有點位資料。")
                    .setPositiveButton("確認刪除") { _, _ ->
                        viewModel.deleteProject(project)
                        showSnackbar("專案已刪除")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNewProjectDialog() {
        val input = android.widget.EditText(this)
        input.hint = "工程名稱"
        
        MaterialAlertDialogBuilder(this)
            .setTitle("新建專案")
            .setView(input)
            .setPositiveButton("建立") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.createProject(name)
                    showSnackbar("專案已建立")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNikonUploadConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Nikon 點位上傳")
            .setMessage("請確保 Nikon 全站儀已進入：\n【MENU】 > 【Communication】 > 【Upload XYZ】\n且畫面顯示「Ready to receive」再點擊開始。")
            .setPositiveButton("開始上傳") { _, _ ->
                viewModel.uploadToNikon()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSnackbar(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun startCamera(line: com.survey.totalstationbt.bluetooth.BluetoothSerialService.ReceivedLine) {
        val point = viewModel.currentPoints.value.find { it.timestamp == line.timestamp } ?: return
        photoPointId = point.id

        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File? = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
        
        // 核心修復：確保目錄存在
        if (storageDir?.exists() == false) {
            storageDir.mkdirs()
        }

        val imageFile = try {
            File.createTempFile("SURVEY_${point.pointName}_${timeStamp}_", ".jpg", storageDir)
        } catch (e: Exception) {
            showSnackbar("無法建立相片檔案：${e.message}")
            return
        }
        
        currentPhotoPath = imageFile.absolutePath

        try {
            val photoURI = FileProvider.getUriForFile(this, "${packageName}.provider", imageFile)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            takePhotoLauncher.launch(intent)
        } catch (e: Exception) {
            showSnackbar("啟動相機失敗：${e.message}")
        }
    }

    private fun sharePoint(line: com.survey.totalstationbt.bluetooth.BluetoothSerialService.ReceivedLine) {
        val pt = line.parsed ?: return
        val text = "點號:${pt.pointName}\nE:${pt.easting}\nN:${pt.northing}\nZ:${pt.elevation}\n編碼:${pt.code}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "分享點位資訊"))
    }
}
