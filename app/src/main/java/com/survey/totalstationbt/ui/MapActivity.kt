package com.survey.totalstationbt.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.survey.totalstationbt.databinding.ActivityMapBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.survey.totalstationbt.db.PointEntity
import com.survey.totalstationbt.utils.SurveyMathUtils

import com.survey.totalstationbt.R
import com.survey.totalstationbt.databinding.DialogProfileBinding
import androidx.recyclerview.widget.LinearLayoutManager

import android.speech.tts.TextToSpeech
import com.survey.totalstationbt.utils.StakeoutCalculator
import java.util.Locale

import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.survey.totalstationbt.model.BaselineAnchor
import com.survey.totalstationbt.model.DxfData
import com.survey.totalstationbt.model.DxfSnapPoint
import com.survey.totalstationbt.model.DxfTapMode
import com.survey.totalstationbt.model.DxfTransform
import com.survey.totalstationbt.model.DxfEntity
import com.survey.totalstationbt.model.SnapType
import com.survey.totalstationbt.parser.DxfParser
import com.survey.totalstationbt.databinding.DialogDxfAlignBinding
import kotlin.math.atan2
import kotlin.math.sqrt

class MapActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMapBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var pointAdapter: PointCheckAdapter

    private val visiblePointIds = mutableSetOf<Long>()
    private var stakeoutTarget: PointEntity? = null
    private var stakeoutTargets: List<PointEntity> = emptyList()
    private var currentStakeoutIndex = 0
    private var elevationBase: PointEntity? = null
    private var baselineA1: BaselineAnchor? = null
    private var baselineA2: BaselineAnchor? = null
    private var tts: TextToSpeech? = null
    private lateinit var stakeoutSheet: BottomSheetBehavior<*>
    private var playbackJob: kotlinx.coroutines.Job? = null
    private var isPlaying = false
    private var lastGuidance: StakeoutCalculator.Guidance? = null
    private var lastImportedUri: android.net.Uri? = null

    private var pendingDxfData: DxfData? = null
    private var currentDxfData: DxfData?
        get() = MainViewModel.cachedDxfData
        set(value) { MainViewModel.cachedDxfData = value }
    private var currentDxfTransform: DxfTransform
        get() = MainViewModel.cachedDxfTransform
        set(value) { MainViewModel.cachedDxfTransform = value }
    // Snap points pre-filled for alignment dialog
    private var presetSnap1: DxfSnapPoint? = null
    private var presetSnap2: DxfSnapPoint? = null
    private val selectedDxfSnaps = mutableListOf<DxfSnapPoint>()

    private val dxfImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        lastImportedUri = uri
        lifecycleScope.launch {
            var errorMsg: String? = null
            val data = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { DxfParser.parse(it) }
                } catch (e: Exception) {
                    errorMsg = e.message
                    null
                }
            }
            if (data == null) {
                showSnackbar("CAD 檔案解析失敗：${errorMsg ?: "未知錯誤"}")
            } else if (data.isEmpty) {
                showSnackbar("CAD 檔案中無支援的圖元 (僅支援 LINE, LWPOLYLINE, CIRCLE, ARC)")
            } else {
                onDxfLoaded(data)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        tts = TextToSpeech(this, this)
        stakeoutSheet = BottomSheetBehavior.from(binding.cardStakeout).apply {
            state = BottomSheetBehavior.STATE_HIDDEN
        }

        setupPointList()
        setupListeners()
        observeData()

        binding.sliderDxfAlpha.addOnChangeListener { _, value, _ ->
            binding.canvasView.dxfAlpha = value.toInt()
        }

        // 恢復 DXF 底圖快取
        currentDxfData?.let { data ->
            binding.canvasView.setDxf(data, currentDxfTransform)
            invalidateOptionsMenu()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.CHINESE
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun setupPointList() {
        pointAdapter = PointCheckAdapter { id, isVisible ->
            if (isVisible) visiblePointIds.add(id) else visiblePointIds.remove(id)
            binding.canvasView.setVisiblePointIds(visiblePointIds)
            pointAdapter.setVisibleIds(visiblePointIds)
        }

        binding.recyclerPointList.apply {
            layoutManager = LinearLayoutManager(this@MapActivity)
            adapter = pointAdapter
        }

        // 搜尋篩選
        binding.etPointSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                pointAdapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.btnSelectAll.setOnClickListener {
            val allIds = viewModel.currentPoints.value.map { it.id }
            visiblePointIds.clear()
            visiblePointIds.addAll(allIds)
            binding.canvasView.setVisiblePointIds(visiblePointIds)
            pointAdapter.setVisibleIds(visiblePointIds)
        }

        binding.btnClearAll.setOnClickListener {
            visiblePointIds.clear()
            binding.canvasView.setVisiblePointIds(visiblePointIds)
            pointAdapter.setVisibleIds(visiblePointIds)
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.map_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_zoom_fit -> { binding.canvasView.autoFit(); true }
            R.id.menu_show_lines -> {
                item.isChecked = !item.isChecked
                binding.canvasView.setShowOriginalLines(item.isChecked)
                true
            }
            R.id.menu_elevation_color -> {
                item.isChecked = !item.isChecked
                binding.canvasView.elevationColorMode = item.isChecked
                val msg = if (item.isChecked) "海拔色帶已開啟（藍低→紅高）" else "已關閉海拔色帶"
                showSnackbar(msg)
                true
            }
            R.id.menu_toggle_3d -> {
                item.isChecked = !item.isChecked
                binding.canvasView.is3DMode = item.isChecked
                binding.toggleGroup3dPresets.visibility =
                    if (item.isChecked) android.view.View.VISIBLE else android.view.View.GONE
                supportActionBar?.title = if (item.isChecked) "3D 立體圖" else "2D 平面圖"
                showSnackbar(if (item.isChecked) "3D 模式：單指旋轉視角，雙指縮放" else "已切換至 2D 平面視圖")
                true
            }
            R.id.menu_points -> {
                if (binding.drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.END))
                    binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.END)
                else
                    binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.END)
                true
            }
            R.id.menu_load_dxf -> {
                dxfImportLauncher.launch("*/*")
                true
            }
            R.id.menu_dxf_layers -> {
                showDxfLayersDialog()
                true
            }
            R.id.menu_playback -> {
                item.isChecked = !item.isChecked
                binding.canvasView.showPlayback = item.isChecked
                binding.cardPlayback.visibility = if (item.isChecked) android.view.View.VISIBLE else android.view.View.GONE
                if (!item.isChecked) stopPlayback()
                showSnackbar(if (item.isChecked) "已開啟軌跡回放" else "已關閉軌跡回放")
                true
            }
            R.id.menu_baseline_list -> {
                val a1 = baselineA1; val a2 = baselineA2
                if (a1 == null || a2 == null) { showSnackbar("尚未設定基準線"); return true }
                showBaselineListDialog(a1, a2)
                true
            }
            R.id.menu_dxf_realign -> {
                val data = currentDxfData ?: run { showSnackbar("尚未載入底圖"); return true }
                showDxfAlignDialog(data, snap1 = presetSnap1, snap2 = presetSnap2)
                true
            }
            R.id.menu_clear_dxf -> {
                pendingDxfData = null; currentDxfData = null
                binding.canvasView.clearDxf()
                binding.canvasView.dxfTapMode = DxfTapMode.NONE
                showSnackbar("底圖已清除")
                true
            }
            R.id.menu_dxf_query -> {
                item.isChecked = !item.isChecked
                val newMode = if (item.isChecked) DxfTapMode.QUERY else DxfTapMode.NONE
                binding.canvasView.dxfTapMode = newMode
                // Ensure snap mode is off
                invalidateOptionsMenu()
                showSnackbar(if (item.isChecked) "CAD 查詢模式：點選圖元查看資訊" else "已關閉 CAD 查詢")
                true
            }
            R.id.menu_dxf_snap -> {
                item.isChecked = !item.isChecked
                val newMode = if (item.isChecked) DxfTapMode.SNAP else DxfTapMode.NONE
                binding.canvasView.dxfTapMode = newMode
                invalidateOptionsMenu()
                showSnackbar(if (item.isChecked) "CAD 放樣模式：點選端點/交點/圓心" else "已關閉放樣模式")
                true
            }
            R.id.menu_dxf_measure -> {
                item.isChecked = !item.isChecked
                val newMode = if (item.isChecked) DxfTapMode.MEASURE else DxfTapMode.NONE
                binding.canvasView.dxfTapMode = newMode
                invalidateOptionsMenu()
                showSnackbar(if (item.isChecked) "CAD 量測模式：連續點選捕捉點，完成後查看結果" else "已關閉量測模式")
                true
            }
            R.id.menu_dxf_measure_result -> {
                val pts = binding.canvasView.getMeasurePoints()
                if (pts.isEmpty()) showSnackbar("尚未加入任何量測點")
                else showMeasureResultDialog(pts)
                true
            }
            R.id.menu_dxf_measure_undo -> {
                binding.canvasView.removeLastMeasurePoint()
                invalidateOptionsMenu()
                val n = binding.canvasView.getMeasurePoints().size
                showSnackbar(if (n == 0) "已清除所有量測點" else "已刪除最後一點（剩餘 $n 點）")
                true
            }
            R.id.menu_dxf_measure_clear -> {
                binding.canvasView.clearMeasurePoints()
                binding.canvasView.setSelectedSnapPoints(emptyList()) // 同步清除視覺標記
                invalidateOptionsMenu()
                showSnackbar("量測點已全部清除")
                true
            }
            R.id.menu_dxf_relative -> {
                item.isChecked = !item.isChecked
                val newMode = if (item.isChecked) DxfTapMode.RELATIVE else DxfTapMode.NONE
                binding.canvasView.dxfTapMode = newMode
                if (!item.isChecked) binding.canvasView.clearRelativePoints()
                invalidateOptionsMenu()
                showSnackbar(if (item.isChecked) "相對位置：點選第一點（A），再點第二點即顯示結果" else "已關閉相對位置模式")
                true
            }
            R.id.menu_dxf_relative_clear -> {
                binding.canvasView.clearRelativePoints()
                invalidateOptionsMenu()
                showSnackbar("基準點已清除")
                true
            }
            R.id.menu_dxf_batch_list -> {
                if (selectedDxfSnaps.isEmpty()) showSnackbar("尚未選取任何 DXF 捕捉點")
                else showDxfBatchStakeoutDialog()
                true
            }
            R.id.menu_help -> {
                startActivity(android.content.Intent(this, HelpActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupListeners() {
        // DXF 圖元查詢回呼
        binding.canvasView.onDxfEntityTapped = { entity -> showDxfEntityInfoDialog(entity) }
        // DXF 捕捉點回呼
        binding.canvasView.onDxfSnapPicked = { snap -> showDxfSnapDialog(snap) }
        // DXF 量測點新增回呼
        binding.canvasView.onMeasureUpdated = { pts -> onMeasurePointAdded(pts) }
        // DXF 相對位置回呼
        binding.canvasView.onRelativePointA = { snap ->
            showSnackbar("基準點 A：${snap.label}  E=${String.format("%.3f", snap.worldE)}, N=${String.format("%.3f", snap.worldN)}  請點選第二點")
        }
        binding.canvasView.onRelativePointsPicked = { a, b -> showRelativePositionDialog(a, b) }

        // 長按點位 → 備忘錄編輯
        binding.canvasView.onLongPressPoint = { pt -> showNoteEditDialog(pt) }

        binding.canvasView.onSelectionChanged = { selected ->
            updateCalcButton(selected)
        }

        binding.fabCalc.setOnClickListener {
            val selected = binding.canvasView.getSelectedPoints()
            if (selected.isEmpty()) return@setOnClickListener
            showCalcDialog(selected)
        }

        // 3D 視角快捷鍵
        binding.btnViewTop.setOnClickListener {
            binding.canvasView.setViewPreset(45f, 89f)
            showSnackbar("俯視（頂視圖）")
        }
        binding.btnViewFront.setOnClickListener {
            binding.canvasView.setViewPreset(0f, 6f)
            showSnackbar("正視（前視圖，E-Z 平面）")
        }
        binding.btnViewSide.setOnClickListener {
            binding.canvasView.setViewPreset(90f, 6f)
            showSnackbar("側視（側視圖，N-Z 平面）")
        }
        binding.btnViewIso.setOnClickListener {
            binding.canvasView.setViewPreset(45f, 30f)
            showSnackbar("等角視圖（預設）")
        }

        setupPlaybackControls()

        binding.btnStopStakeout.setOnClickListener {
            stopStakeout()
        }

        binding.btnNextStakeout.setOnClickListener {
            if (currentStakeoutIndex < stakeoutTargets.size - 1) {
                currentStakeoutIndex++
                updateBatchStakeoutUI()
            } else {
                showSnackbar("已到達最後一個放樣點")
            }
        }

        binding.btnPrevStakeout.setOnClickListener {
            if (currentStakeoutIndex > 0) {
                currentStakeoutIndex--
                updateBatchStakeoutUI()
            }
        }

        binding.btnShareStakeout.setOnClickListener {
            shareStakeoutGuidance()
        }
    }

    private fun setupPlaybackControls() {
        binding.sliderPlayback.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.canvasView.playbackProgress = value
                binding.tvPlaybackInfo.text = "${(value * 100).toInt()}%"
            }
        }

        binding.btnPlayPause.setOnClickListener {
            if (isPlaying) pausePlayback() else startPlayback()
        }
    }

    private fun startPlayback() {
        isPlaying = true
        binding.btnPlayPause.setIconResource(R.drawable.ic_pause)
        val currentProgress = binding.canvasView.playbackProgress
        val startFrom = if (currentProgress >= 1f) 0f else currentProgress
        
        playbackJob = lifecycleScope.launch {
            var p = startFrom
            while (p < 1f && isPlaying) {
                p += 0.01f
                binding.canvasView.playbackProgress = p
                binding.sliderPlayback.value = p
                binding.tvPlaybackInfo.text = "${(p * 100).toInt()}%"
                kotlinx.coroutines.delay(50)
            }
            if (p >= 1f) pausePlayback()
        }
    }

    private fun pausePlayback() {
        isPlaying = false
        playbackJob?.cancel()
        binding.btnPlayPause.setIconResource(R.drawable.ic_play)
    }

    private fun stopPlayback() {
        pausePlayback()
        binding.canvasView.playbackProgress = 1.0f
        binding.sliderPlayback.value = 1.0f
        binding.tvPlaybackInfo.text = "100%"
    }

    private fun updateCalcButton(selected: List<PointEntity>) {
        if (selected.isEmpty()) {
            binding.fabCalc.hide()
        } else {
            binding.fabCalc.show()
        }
    }

    private fun showCalcDialog(selected: List<PointEntity>) {
        val message = buildString {
            if (selected.size == 1) {
                append("【單點資訊】\n")
                append("點號：${selected[0].pointName}\n")
                append("座標：E=${String.format("%.3f", selected[0].easting ?: 0.0)}, N=${String.format("%.3f", selected[0].northing ?: 0.0)}\n")
                append("高程：Z=${String.format("%.3f", selected[0].elevation ?: 0.0)}")
                if (elevationBase != null) {
                    val dz = (selected[0].elevation ?: 0.0) - (elevationBase!!.elevation ?: 0.0)
                    append("\n與基準高差：${String.format("%+.3f", dz)} m")
                }
                if (selected[0].note.isNotEmpty()) {
                    append("\n\n📝 備忘：${selected[0].note}")
                }
            } else if (selected.size == 2) {
                val p1 = selected[0]; val p2 = selected[1]
                val dist = SurveyMathUtils.calculateDistance2D(p1, p2)
                val azimuth = SurveyMathUtils.calculateAzimuth(p1, p2)
                append("【兩點計算】\n")
                append("起點：${p1.pointName}\n")
                append("終點：${p2.pointName}\n")
                append("距離：${String.format("%.3f", dist ?: 0.0)} m\n")
                append("方位角：${String.format("%.4f", azimuth ?: 0.0)}°\n")
                append("高差：${String.format("%+.3f", (p2.elevation ?: 0.0) - (p1.elevation ?: 0.0))} m")
                
                if (baselineA1?.name == p1.pointName && baselineA2?.name == p2.pointName) {
                    append("\n[已設為目前基準線]")
                }
            } else if (selected.size >= 3) {
                val area = SurveyMathUtils.calculateArea(selected)
                val perimeter = SurveyMathUtils.calculatePerimeter(selected)
                
                append("【多點計算】\n")
                append("選取順序：${selected.joinToString("→") { it.pointName }}\n\n")
                
                append("【頂點角度】\n")
                for (i in selected.indices) {
                    val pPrev = selected[if (i == 0) selected.size - 1 else i - 1]
                    val pCurr = selected[i]
                    val pNext = selected[(i + 1) % selected.size]
                    val angle = SurveyMathUtils.calculateAngle(pPrev, pCurr, pNext)
                    append("點 ${pCurr.pointName}：${String.format("%.4f", angle ?: 0.0)}°\n")
                }
                
                append("\n閉合周長：${String.format("%.3f", perimeter)} m")
                append("\n閉合面積：${String.format("%.3f", area)} m²")
                append("\n(${String.format("%.4f", area / 3.3058)} 坪)")
            } else {
                append("請選取 1 個點設定基準或放樣，或多點進行計算。")
            }
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("測量計算")
            .setMessage(message)
            .setPositiveButton("關閉", null)
            .setNeutralButton("清除選取") { _, _ ->
                binding.canvasView.clearSelection()
            }
        
        if (elevationBase != null || baselineA1 != null) {
            builder.setNeutralButton("取消基準/基線") { _, _ ->
                elevationBase = null
                baselineA1 = null
                baselineA2 = null
                binding.canvasView.setElevationBase(null)
                binding.canvasView.setBaseline(null as BaselineAnchor?, null)
                binding.canvasView.clearSelection()
                showSnackbar("已取消所有基準設定")
            }
        }
        
        if (selected.size == 1) {
            builder.setPositiveButton("設為高程基準") { _, _ ->
                elevationBase = selected[0]
                binding.canvasView.setElevationBase(elevationBase)
                showSnackbar("已將 ${selected[0].pointName} 設為零點基準")
            }
            builder.setNegativeButton("開始放樣") { _, _ ->
                startStakeout(listOf(selected[0]))
            }
        } else if (selected.size >= 2) {
            if (selected.size == 2) {
                builder.setPositiveButton("設為基準線") { _, _ ->
                    baselineA1 = BaselineAnchor.from(selected[0])
                    baselineA2 = BaselineAnchor.from(selected[1])
                    binding.canvasView.setBaseline(baselineA1, baselineA2)
                    showSnackbar("基準線已設定：${baselineA1!!.name} -> ${baselineA2!!.name}")
                }
            }
            builder.setNegativeButton("批次放樣") { _, _ ->
                startStakeout(selected)
            }
            builder.setNeutralButton("顯示剖面圖") { _, _ ->
                showProfileDialog(selected)
            }
        }
        
        builder.show()
    }

    private fun startStakeout(targets: List<PointEntity>) {
        if (targets.isEmpty()) return
        stakeoutTargets = targets
        currentStakeoutIndex = 0
        updateBatchStakeoutUI()
        stakeoutSheet.state = BottomSheetBehavior.STATE_EXPANDED
        showSnackbar(if (targets.size > 1) "已進入批次放樣模式 (共 ${targets.size} 點)" else "已進入放樣模式")
    }

    private fun updateBatchStakeoutUI() {
        if (currentStakeoutIndex !in stakeoutTargets.indices) return
        val target = stakeoutTargets[currentStakeoutIndex]
        stakeoutTarget = target
        binding.canvasView.setStakeoutTarget(target)
        binding.tvStakeoutTarget.text = "正在放樣：${target.pointName}"
        
        if (stakeoutTargets.size > 1) {
            binding.tvStakeoutProgress.visibility = android.view.View.VISIBLE
            binding.tvStakeoutProgress.text = "(${currentStakeoutIndex + 1}/${stakeoutTargets.size})"
            binding.layoutBatchControls.visibility = android.view.View.VISIBLE
        } else {
            binding.tvStakeoutProgress.visibility = android.view.View.GONE
            binding.layoutBatchControls.visibility = android.view.View.GONE
        }
        
        // 自動將地圖中心對準放樣點
        binding.canvasView.centerOnPoint(target)
    }

    private fun stopStakeout() {
        stakeoutTarget = null
        stakeoutTargets = emptyList()
        currentStakeoutIndex = 0
        lastGuidance = null
        binding.canvasView.setStakeoutTarget(null)
        baselineA1 = null
        baselineA2 = null
        binding.canvasView.setBaseline(null as BaselineAnchor?, null)
        stakeoutSheet.state = BottomSheetBehavior.STATE_HIDDEN
        binding.canvasView.clearSelection()
        binding.tvStakeoutProgress.visibility = android.view.View.GONE
        binding.layoutBatchControls.visibility = android.view.View.GONE
    }

    private fun showProfileDialog(selected: List<PointEntity>) {
        val dialogBinding = DialogProfileBinding.inflate(layoutInflater)
        dialogBinding.profileView.setProfilePoints(selected)

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("關閉", null)
            .show()
    }

    private fun observeData() {
        lifecycleScope.launch {
            viewModel.dxfLoadedEvent.collect {
                currentDxfData?.let { data ->
                    binding.canvasView.setDxf(data, currentDxfTransform)
                    binding.canvasView.autoFit()
                    invalidateOptionsMenu()
                }
            }
        }
        lifecycleScope.launch {
            viewModel.currentPoints.collectLatest { points ->
                binding.canvasView.setPoints(points)
                pointAdapter.submitPoints(points)
                
                // 處理放樣邏輯
                val lastPoint = points.lastOrNull()
                val target = stakeoutTarget
                if (lastPoint != null && target != null) {
                    updateStakeoutGuidance(lastPoint, target)
                }

                // 預設全選
                if (visiblePointIds.isEmpty() && points.isNotEmpty()) {
                    visiblePointIds.addAll(points.map { it.id })
                    binding.canvasView.setVisiblePointIds(visiblePointIds)
                    pointAdapter.setVisibleIds(visiblePointIds)
                }
            }
        }
    }

    private fun updateStakeoutGuidance(current: PointEntity, target: PointEntity) {
        val guidance = StakeoutCalculator.getGuidance(current, target) ?: return
        lastGuidance = guidance
        val elevationOnly = binding.checkElevationOnly.isChecked
        
        binding.tvDistDelta.text = "距離目標：${String.format("%.3f", guidance.distance2D)} m"
        
        val zIcon = if (guidance.deltaZ > 0.005) " 🔼" else if (guidance.deltaZ < -0.005) " 🔽" else " ✅"
        binding.tvHeightDelta.text = "填挖：${String.format("%+.3f", guidance.deltaZ)} m$zIcon"
        
        // 如果是「僅提示填挖」，可以弱化平面導引 (例如隱藏箭頭或變色)
        if (elevationOnly) {
            binding.imgDirection.alpha = 0.3f
            binding.tvDistDelta.alpha = 0.5f
        } else {
            binding.imgDirection.alpha = 1.0f
            binding.tvDistDelta.alpha = 1.0f
        }

        // 旋轉箭頭 (azimuth 是相對於北方的角度，ImageView 旋轉也是)
        binding.imgDirection.rotation = guidance.directionArrow
        
        // 變換顏色
        if (guidance.distance2D < 0.05) {
            binding.tvDistDelta.setTextColor(getColor(R.color.status_connected))
            binding.imgDirection.setColorFilter(getColor(R.color.status_connected))
            
            // 批次模式自動跳轉：若距離小於 5cm 且還有下一點
            if (stakeoutTargets.size > 1 && currentStakeoutIndex < stakeoutTargets.size - 1) {
                currentStakeoutIndex++
                updateBatchStakeoutUI()
                showSnackbar("已到達目標範圍，自動切換至下一點")
            }
        } else {
            binding.tvDistDelta.setTextColor(getColor(R.color.status_connecting))
            binding.imgDirection.setColorFilter(getColor(R.color.status_connecting))
        }

        // 語音導引 (每 5 秒播報一次，或是當距離變化大時)
        // 這裡先簡單實現，之後可以加頻率控制
        tts?.speak(StakeoutCalculator.getVoiceCommand(guidance, elevationOnly), TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun shareStakeoutGuidance() {
        val target = stakeoutTarget ?: return
        val guidance = lastGuidance ?: return
        
        val zIcon = if (guidance.deltaZ > 0.005) "🔼 填方" else if (guidance.deltaZ < -0.005) "🔽 挖方" else "✅ 達標"
        
        val nStr = if (guidance.deltaN > 0.01) "N ${String.format("%.2f", guidance.deltaN)}m" 
                   else if (guidance.deltaN < -0.01) "S ${String.format("%.2f", kotlin.math.abs(guidance.deltaN))}m" 
                   else "-"
        val eStr = if (guidance.deltaE > 0.01) "E ${String.format("%.2f", guidance.deltaE)}m" 
                   else if (guidance.deltaE < -0.01) "W ${String.format("%.2f", kotlin.math.abs(guidance.deltaE))}m" 
                   else "-"

        // 建立簡易方位圖示
        val grid = buildString {
            append("      [ ${if(guidance.deltaN > 0.01) "↑" else " "} ]\n")
            append("[ ${if(guidance.deltaE < -0.01) "←" else " "} ]  🎯  [ ${if(guidance.deltaE > 0.01) "→" else " "} ]\n")
            append("      [ ${if(guidance.deltaN < -0.01) "↓" else " "} ]\n")
        }

        val text = buildString {
            append("【放樣導引資訊】\n")
            append("🎯 目標點：${target.pointName}\n")
            append("📏 距離：${String.format("%.3f", guidance.distance2D)} m\n")
            append("📐 高程：$zIcon (${String.format("%+.3f", guidance.deltaZ)} m)\n")
            append("────────────────\n")
            append("🧭 移動建議：\n")
            append(grid)
            append("北(N)/南(S)：$nStr\n")
            append("東(E)/西(W)：$eStr\n")
            append("────────────────\n")
            append("生成時間：${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        }

        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        startActivity(android.content.Intent.createChooser(intent, "分享導引資訊"))
    }

    private fun showNoteEditDialog(pt: PointEntity) {
        val input = android.widget.EditText(this).apply {
            setText(pt.note)
            hint = "輸入備忘錄…"
            minLines = 3
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setPadding(48, 24, 48, 24)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("📝 備忘錄：${pt.pointName}")
            .setView(input)
            .setPositiveButton("儲存") { _, _ ->
                viewModel.updatePointNote(pt.id, input.text.toString().trim())
                showSnackbar("備忘錄已儲存")
            }
            .setNeutralButton("清除") { _, _ ->
                viewModel.updatePointNote(pt.id, "")
                showSnackbar("備忘錄已清除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        val mode = binding.canvasView.dxfTapMode
        menu.findItem(R.id.menu_playback)?.isChecked     = binding.canvasView.showPlayback
        menu.findItem(R.id.menu_dxf_query)?.isChecked   = (mode == DxfTapMode.QUERY)
        menu.findItem(R.id.menu_dxf_snap)?.isChecked    = (mode == DxfTapMode.SNAP)
        menu.findItem(R.id.menu_dxf_measure)?.isChecked   = (mode == DxfTapMode.MEASURE)
        menu.findItem(R.id.menu_dxf_relative)?.isChecked = (mode == DxfTapMode.RELATIVE)
        val hasDxf = currentDxfData != null
        menu.findItem(R.id.menu_dxf_layers)?.isEnabled         = hasDxf
        menu.findItem(R.id.menu_dxf_query)?.isEnabled          = hasDxf
        menu.findItem(R.id.menu_dxf_snap)?.isEnabled           = hasDxf
        menu.findItem(R.id.menu_dxf_measure)?.isEnabled        = hasDxf
        menu.findItem(R.id.menu_dxf_relative)?.isEnabled       = hasDxf
        menu.findItem(R.id.menu_dxf_batch_list)?.isEnabled     = selectedDxfSnaps.isNotEmpty()
        menu.findItem(R.id.menu_baseline_list)?.isEnabled      = (baselineA1 != null && baselineA2 != null)
        menu.findItem(R.id.menu_dxf_realign)?.isEnabled        = hasDxf
        menu.findItem(R.id.menu_clear_dxf)?.isEnabled          = hasDxf
        val hasMeasure = binding.canvasView.getMeasurePoints().isNotEmpty()
        menu.findItem(R.id.menu_dxf_measure_result)?.isEnabled = hasMeasure
        menu.findItem(R.id.menu_dxf_measure_undo)?.isEnabled   = hasMeasure
        menu.findItem(R.id.menu_dxf_measure_clear)?.isEnabled  = hasMeasure
        return super.onPrepareOptionsMenu(menu)
    }

    private fun onDxfLoaded(data: DxfData) {
        currentDxfData = data
        if (data.isRealWorldCoord) {
            currentDxfTransform = DxfTransform.IDENTITY
            binding.canvasView.setDxf(data, DxfTransform.IDENTITY)
            binding.canvasView.autoFit()
            invalidateOptionsMenu()
            // 如果是真實座標且有 URI，則直接存入專案
            lastImportedUri?.let { viewModel.saveDxfToProject(it, DxfTransform.IDENTITY) }
            showSnackbar("底圖載入成功（TWD97 真實世界座標，${data.entities.size} 個圖元）")
        } else {
            pendingDxfData = data
            // Show DXF in local coords immediately so user can see building plan and snap reference points
            currentDxfTransform = DxfTransform.IDENTITY
            binding.canvasView.setDxf(data, DxfTransform.IDENTITY)
            binding.canvasView.autoFit()
            invalidateOptionsMenu()
            showDxfAlignDialog(data)
        }
    }

    private fun showDxfAlignDialog(data: DxfData, snap1: DxfSnapPoint? = null, snap2: DxfSnapPoint? = null) {
        val dialogBinding = DialogDxfAlignBinding.inflate(layoutInflater)

        // 模式切換
        dialogBinding.toggleAlignMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            dialogBinding.layoutTwoPoint.visibility =
                if (checkedId == R.id.btnModeTwoPoint) android.view.View.VISIBLE else android.view.View.GONE
            dialogBinding.layoutManual.visibility =
                if (checkedId == R.id.btnModeManual) android.view.View.VISIBLE else android.view.View.GONE
        }
        // 預設手動輸入
        dialogBinding.toggleAlignMode.check(R.id.btnModeManual)
        dialogBinding.layoutTwoPoint.visibility = android.view.View.GONE
        dialogBinding.layoutManual.visibility = android.view.View.VISIBLE

        // 預填捕捉點座標
        snap1?.let {
            dialogBinding.etA1x.setText(String.format("%.4f", it.dxfX))
            dialogBinding.etA1y.setText(String.format("%.4f", it.dxfY))
        }
        snap2?.let {
            dialogBinding.etA2x.setText(String.format("%.4f", it.dxfX))
            dialogBinding.etA2y.setText(String.format("%.4f", it.dxfY))
        }

        // 從已匯入點位選取 E/N
        dialogBinding.btnPickPoint1.setOnClickListener {
            showPointPickerFor(dialogBinding.etA1e, dialogBinding.etA1n)
        }
        dialogBinding.btnPickPoint2.setOnClickListener {
            showPointPickerFor(dialogBinding.etA2e, dialogBinding.etA2n)
        }

        // 即時預覽：每次輸入變更都重算
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                updateAlignPreview(dialogBinding, data)
            }
        }
        listOf(
            dialogBinding.etA1x, dialogBinding.etA1y, dialogBinding.etA1e, dialogBinding.etA1n,
            dialogBinding.etA2x, dialogBinding.etA2y, dialogBinding.etA2e, dialogBinding.etA2n,
            dialogBinding.etManualDx, dialogBinding.etManualDy,
            dialogBinding.etManualScale, dialogBinding.etManualRotation
        ).forEach { it.addTextChangedListener(watcher) }

        MaterialAlertDialogBuilder(this)
            .setTitle("DXF 底圖對齊設定")
            .setView(dialogBinding.root)
            .setPositiveButton("套用") { _, _ ->
                val transform = buildTransform(dialogBinding) ?: run {
                    showSnackbar("對齊參數不完整或無效")
                    return@setPositiveButton
                }
                currentDxfTransform = transform
                binding.canvasView.setDxf(data, transform)
                binding.canvasView.autoFit()
                invalidateOptionsMenu()
                
                // 持久化到專案中
                lastImportedUri?.let {
                    viewModel.saveDxfToProject(it, transform)
                } ?: run {
                    viewModel.updateDxfTransform(transform)
                }

                presetSnap1 = null; presetSnap2 = null
                showSnackbar("底圖對齊完成（${data.entities.size} 個圖元）")
            }
            .setNegativeButton("取消") { _, _ ->
                showSnackbar("底圖已載入（本地座標），可從選單重新設定對齊")
            }
            .show()
    }

    private fun updateAlignPreview(dialogBinding: DialogDxfAlignBinding, @Suppress("UNUSED_PARAMETER") data: DxfData) {
        val transform = buildTransform(dialogBinding) ?: return
        val scaleStr = String.format("%.6f", transform.scale)
        val rotDeg = Math.toDegrees(transform.rotationRad)
        val rotStr = String.format("%.4f°", rotDeg)
        dialogBinding.tvAlignResult.visibility = android.view.View.VISIBLE
        dialogBinding.tvAlignResult.text =
            "dx=${String.format("%.3f", transform.dx)} m  dy=${String.format("%.3f", transform.dy)} m\n" +
            "scale=$scaleStr  rot=$rotStr"
    }

    private fun buildTransform(dialogBinding: DialogDxfAlignBinding): DxfTransform? {
        val isTwoPoint = dialogBinding.layoutTwoPoint.visibility == android.view.View.VISIBLE
        return if (isTwoPoint) {
            val x1 = dialogBinding.etA1x.text?.toString()?.toDoubleOrNull() ?: return null
            val y1 = dialogBinding.etA1y.text?.toString()?.toDoubleOrNull() ?: return null
            val e1 = dialogBinding.etA1e.text?.toString()?.toDoubleOrNull() ?: return null
            val n1 = dialogBinding.etA1n.text?.toString()?.toDoubleOrNull() ?: return null
            val x2 = dialogBinding.etA2x.text?.toString()?.toDoubleOrNull() ?: return null
            val y2 = dialogBinding.etA2y.text?.toString()?.toDoubleOrNull() ?: return null
            val e2 = dialogBinding.etA2e.text?.toString()?.toDoubleOrNull() ?: return null
            val n2 = dialogBinding.etA2n.text?.toString()?.toDoubleOrNull() ?: return null
            DxfTransform.fromTwoPoints(x1, y1, e1, n1, x2, y2, e2, n2)
        } else {
            val dx  = dialogBinding.etManualDx.text?.toString()?.toDoubleOrNull() ?: 0.0
            val dy  = dialogBinding.etManualDy.text?.toString()?.toDoubleOrNull() ?: 0.0
            val sc  = dialogBinding.etManualScale.text?.toString()?.toDoubleOrNull() ?: 1.0
            val rot = dialogBinding.etManualRotation.text?.toString()?.toDoubleOrNull() ?: 0.0
            if (sc <= 0.0) return null
            DxfTransform(dx, dy, sc, Math.toRadians(rot))
        }
    }

    private fun onMeasurePointAdded(pts: List<DxfSnapPoint>) {
        invalidateOptionsMenu()
        val n = pts.size
        if (n == 1) {
            showSnackbar("量測點 1：E=${String.format("%.3f", pts[0].worldE)}, N=${String.format("%.3f", pts[0].worldN)}")
            return
        }
        val last = pts[n - 1]; val prev = pts[n - 2]
        val dist = sqrt((last.worldE - prev.worldE) * (last.worldE - prev.worldE) +
                        (last.worldN - prev.worldN) * (last.worldN - prev.worldN))
        var az = Math.toDegrees(atan2(last.worldE - prev.worldE, last.worldN - prev.worldN))
        if (az < 0) az += 360.0
        val total = (1 until n).sumOf { i ->
            sqrt((pts[i].worldE - pts[i-1].worldE) * (pts[i].worldE - pts[i-1].worldE) +
                 (pts[i].worldN - pts[i-1].worldN) * (pts[i].worldN - pts[i-1].worldN))
        }
        val msg = buildString {
            append("點${n}  距離：${String.format("%.3f", dist)} m")
            append("  方位：${String.format("%.2f", az)}°")
            if (n >= 3) append("  累計：${String.format("%.3f", total)} m")
        }
        showSnackbar(msg)
    }

    private fun showRelativePositionDialog(a: DxfSnapPoint, b: DxfSnapPoint) {
        val dE = b.worldE - a.worldE
        val dN = b.worldN - a.worldN
        val dist = sqrt(dE * dE + dN * dN)
        var az = Math.toDegrees(atan2(dE, dN)); if (az < 0) az += 360.0
        val backAz = if (az >= 180.0) az - 180.0 else az + 180.0

        val msg = buildString {
            append("【起點 A】${a.label}\n")
            append("  E=${String.format("%.4f", a.worldE)}, N=${String.format("%.4f", a.worldN)}\n\n")
            append("【終點 B】${b.label}\n")
            append("  E=${String.format("%.4f", b.worldE)}, N=${String.format("%.4f", b.worldN)}\n\n")
            append("─────────────────────────\n")
            append("ΔE（東）：${String.format("%+.4f", dE)} m\n")
            append("ΔN（北）：${String.format("%+.4f", dN)} m\n")
            append("距 離：${String.format("%.4f", dist)} m\n")
            append("方位角 A→B：${String.format("%.4f", az)}°\n")
            append("反方位角：${String.format("%.4f", backAz)}°")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("相對位置計算")
            .setMessage(msg)
            .setPositiveButton("關閉", null)
            .setNeutralButton("互換 A↔B") { _, _ ->
                showRelativePositionDialog(b, a)
            }
            .show()
    }

    private fun showMeasureResultDialog(pts: List<DxfSnapPoint>) {
        val n = pts.size
        val sb = StringBuilder()

        // 點位座標表
        sb.append("【量測點座標】\n")
        pts.forEachIndexed { i, s ->
            sb.append("  點${i+1} (${s.label})  E=${String.format("%.3f", s.worldE)}  N=${String.format("%.3f", s.worldN)}\n")
        }

        // 各段長度與方位角
        sb.append("\n【各段距離與方位角】\n")
        var totalLen = 0.0
        for (i in 1 until n) {
            val s1 = pts[i - 1]; val s2 = pts[i]
            val d = sqrt((s2.worldE - s1.worldE) * (s2.worldE - s1.worldE) +
                         (s2.worldN - s1.worldN) * (s2.worldN - s1.worldN))
            var az = Math.toDegrees(atan2(s2.worldE - s1.worldE, s2.worldN - s1.worldN))
            if (az < 0) az += 360.0
            totalLen += d
            sb.append("  段${i}→${i+1}：${String.format("%.4f", d)} m  方位 ${String.format("%.4f", az)}°\n")
        }
        sb.append("  ──────────────────────────\n")
        sb.append("  總長度：${String.format("%.4f", totalLen)} m\n")

        // 角度（每個頂點的內角）
        if (n >= 3) {
            sb.append("\n【頂點角度】\n")
            for (i in 1 until n - 1) {
                val p0 = pts[i - 1]; val p1 = pts[i]; val p2 = pts[i + 1]
                val v1x = p0.worldE - p1.worldE; val v1y = p0.worldN - p1.worldN
                val v2x = p2.worldE - p1.worldE; val v2y = p2.worldN - p1.worldN
                val dot  = v1x * v2x + v1y * v2y
                val mag1 = sqrt(v1x * v1x + v1y * v1y)
                val mag2 = sqrt(v2x * v2x + v2y * v2y)
                val angle = if (mag1 > 1e-12 && mag2 > 1e-12)
                    Math.toDegrees(Math.acos((dot / (mag1 * mag2)).coerceIn(-1.0, 1.0))) else 0.0
                sb.append("  點${i+1}：${String.format("%.4f", angle)}°\n")
            }

            // 面積與周長（閉合多邊形）
            var area = 0.0
            for (i in pts.indices) {
                val j = (i + 1) % n
                area += pts[i].worldE * pts[j].worldN - pts[j].worldE * pts[i].worldN
            }
            area = kotlin.math.abs(area) / 2.0
            var perimeter = totalLen
            val closingSeg = sqrt(
                (pts[0].worldE - pts[n-1].worldE) * (pts[0].worldE - pts[n-1].worldE) +
                (pts[0].worldN - pts[n-1].worldN) * (pts[0].worldN - pts[n-1].worldN))
            perimeter += closingSeg
            sb.append("\n【閉合多邊形】\n")
            sb.append("  周長：${String.format("%.4f", perimeter)} m\n")
            sb.append("  面積：${String.format("%.4f", area)} m²\n")
            sb.append("      ＝${String.format("%.4f", area / 3.3058)} 坪\n")
            sb.append("      ＝${String.format("%.6f", area / 10000.0)} 公頃")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("CAD 量測結果（${n} 點）")
            .setMessage(sb.toString())
            .setPositiveButton("關閉", null)
            .setNeutralButton("清除量測") { _, _ ->
                binding.canvasView.clearMeasurePoints()
                invalidateOptionsMenu()
            }
            .show()
    }

    private fun showPointPickerFor(
        etE: android.widget.EditText,
        etN: android.widget.EditText
    ) {
        val points = viewModel.currentPoints.value
        if (points.isEmpty()) { showSnackbar("尚無已匯入點位"); return }
        val items = points.map { pt ->
            "${pt.pointName}  E=${String.format("%.3f", pt.easting ?: 0.0)}  N=${String.format("%.3f", pt.northing ?: 0.0)}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("選擇點位作為世界座標")
            .setItems(items) { _, which ->
                val pt = points[which]
                etE.setText(String.format("%.4f", pt.easting ?: 0.0))
                etN.setText(String.format("%.4f", pt.northing ?: 0.0))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDxfEntityInfoDialog(entity: DxfEntity) {
        val tf = currentDxfTransform
        val (title, message) = when (entity) {
            is DxfEntity.Line -> {
                val (e1, n1) = tf.toWorld(entity.x1, entity.y1)
                val (e2, n2) = tf.toWorld(entity.x2, entity.y2)
                val length = sqrt((e2 - e1) * (e2 - e1) + (n2 - n1) * (n2 - n1))
                var az = Math.toDegrees(atan2(e2 - e1, n2 - n1))
                if (az < 0) az += 360.0
                "直線  [圖層: ${entity.layer}]" to buildString {
                    append("長度：${String.format("%.4f", length)} m\n")
                    append("方位角：${String.format("%.4f", az)}°\n")
                    append("起點  E=${String.format("%.3f", e1)}, N=${String.format("%.3f", n1)}\n")
                    append("終點  E=${String.format("%.3f", e2)}, N=${String.format("%.3f", n2)}")
                }
            }
            is DxfEntity.Circle -> {
                val (ce, cn) = tf.toWorld(entity.cx, entity.cy)
                val r = entity.radius * tf.scale
                "圓  [圖層: ${entity.layer}]" to buildString {
                    append("半徑：${String.format("%.4f", r)} m\n")
                    append("直徑：${String.format("%.4f", r * 2)} m\n")
                    append("圓心  E=${String.format("%.3f", ce)}, N=${String.format("%.3f", cn)}")
                }
            }
            is DxfEntity.Arc -> {
                val (ce, cn) = tf.toWorld(entity.cx, entity.cy)
                val r = entity.radius * tf.scale
                var span = entity.endDeg - entity.startDeg
                if (span <= 0) span += 360.0
                val arcLen = r * Math.toRadians(span)
                "圓弧  [圖層: ${entity.layer}]" to buildString {
                    append("半徑：${String.format("%.4f", r)} m\n")
                    append("弧長：${String.format("%.4f", arcLen)} m\n")
                    append("張角：${String.format("%.2f", span)}°")
                    append("  (${String.format("%.2f", entity.startDeg)}° → ${String.format("%.2f", entity.endDeg)}°)\n")
                    append("圓心  E=${String.format("%.3f", ce)}, N=${String.format("%.3f", cn)}")
                }
            }
            is DxfEntity.Polyline -> {
                var totalLen = 0.0
                val segSb = StringBuilder()
                entity.vertices.zipWithNext().forEachIndexed { i, (v1, v2) ->
                    val (e1, n1) = tf.toWorld(v1.x.toDouble(), v1.y.toDouble())
                    val (e2, n2) = tf.toWorld(v2.x.toDouble(), v2.y.toDouble())
                    val seg = sqrt((e2 - e1) * (e2 - e1) + (n2 - n1) * (n2 - n1))
                    totalLen += seg
                    if (entity.vertices.size <= 10) segSb.append("  段${i + 1}: ${String.format("%.4f", seg)} m\n")
                }
                "折線  [圖層: ${entity.layer}]" to buildString {
                    append("頂點數：${entity.vertices.size}  閉合：${if (entity.closed) "是" else "否"}\n")
                    append("總長：${String.format("%.4f", totalLen)} m\n")
                    if (entity.vertices.size <= 10) append(segSb)
                }
            }
            is DxfEntity.Point -> {
                val (e, n) = tf.toWorld(entity.x, entity.y)
                "點  [圖層: ${entity.layer}]" to buildString {
                    append("座標  E=${String.format("%.3f", e)}, N=${String.format("%.3f", n)}")
                }
            }
            is DxfEntity.Text -> {
                val (e, n) = tf.toWorld(entity.x, entity.y)
                "文字  [圖層: ${entity.layer}]" to buildString {
                    append("內容：${entity.text}\n")
                    append("高度：${String.format("%.2f", entity.height * tf.scale)} m\n")
                    append("旋轉：${String.format("%.2f", entity.rotation)}°\n")
                    append("插入點  E=${String.format("%.3f", e)}, N=${String.format("%.3f", n)}")
                }
            }
            is DxfEntity.Ellipse -> {
                val (ce, cn) = tf.toWorld(entity.cx, entity.cy)
                val majorLen = sqrt(entity.mx * entity.mx + entity.my * entity.my) * tf.scale
                "橢圓  [圖層: ${entity.layer}]" to buildString {
                    append("長軸：${String.format("%.4f", majorLen * 2)} m\n")
                    append("短軸：${String.format("%.4f", majorLen * entity.ratio * 2)} m\n")
                    append("中心  E=${String.format("%.3f", ce)}, N=${String.format("%.3f", cn)}")
                }
            }
            else -> "未支援圖元" to "此類型圖元 (${entity::class.simpleName}) 尚未支援詳細資訊顯示"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("DXF 圖元資訊 — $title")
            .setMessage(message)
            .setPositiveButton("關閉", null)
            .show()
    }

    private sealed class DxfSnapAction {
        object Stakeout : DxfSnapAction()
        object BatchToggle : DxfSnapAction()
        object Align1 : DxfSnapAction()
        object Align2 : DxfSnapAction()
        object BaselineP1 : DxfSnapAction()
        object BaselineP2 : DxfSnapAction()
        object ClearBaseline : DxfSnapAction()
        object Dismiss : DxfSnapAction()
    }

    private fun showDxfSnapDialog(snap: DxfSnapPoint) {
        val typeLabel = when (snap.type) {
            SnapType.ENDPOINT     -> "端點"
            SnapType.MIDPOINT     -> "中點"
            SnapType.CENTER       -> "圓心"
            SnapType.INTERSECTION -> "交點"
        }
        
        // 將資訊放在標題，避免與 setItems 衝突
        val title = "捕捉點：$typeLabel\nE=${String.format("%.3f", snap.worldE)}, N=${String.format("%.3f", snap.worldN)}"
        
        val isAlreadySelected = selectedDxfSnaps.any { it.worldE == snap.worldE && it.worldN == snap.worldN }
        val hasBaseline = baselineA1 != null || baselineA2 != null

        val actions = mutableListOf<Pair<String, DxfSnapAction>>().apply {
            add("🎯 加入放樣清單（立即導引）"                             to DxfSnapAction.Stakeout)
            add((if (isAlreadySelected) "➖ 移除批次選取" else "➕ 加入批次選取（稍後放樣）") to DxfSnapAction.BatchToggle)
            add("📍 設為對齊錨點 1"                                      to DxfSnapAction.Align1)
            add("📍 設為對齊錨點 2"                                      to DxfSnapAction.Align2)
            add("📏 設為基準線起點 (P1)"                                  to DxfSnapAction.BaselineP1)
            add("📏 設為基準線終點 (P2)"                                  to DxfSnapAction.BaselineP2)
            if (hasBaseline) add("🗑 清除基準線"                          to DxfSnapAction.ClearBaseline)
            add("❌ 關閉"                                                 to DxfSnapAction.Dismiss)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { dialog, which ->
                when (actions[which].second) {
                    DxfSnapAction.Stakeout -> {
                        startStakeout(listOf(snapToPointEntity(snap)))
                    }
                    DxfSnapAction.BatchToggle -> {
                        if (isAlreadySelected) {
                            selectedDxfSnaps.removeAll { it.worldE == snap.worldE && it.worldN == snap.worldN }
                            showSnackbar("已從清單移除")
                        } else {
                            selectedDxfSnaps.add(snap)
                            showSnackbar("已加入選取清單（共 ${selectedDxfSnaps.size} 點）")
                        }
                        binding.canvasView.setSelectedSnapPoints(selectedDxfSnaps)
                        invalidateOptionsMenu()
                    }
                    DxfSnapAction.Align1 -> {
                        presetSnap1 = snap
                        currentDxfData?.let { showDxfAlignDialog(it, snap1 = presetSnap1, snap2 = presetSnap2) }
                            ?: showSnackbar("尚未載入底圖")
                    }
                    DxfSnapAction.Align2 -> {
                        presetSnap2 = snap
                        currentDxfData?.let { showDxfAlignDialog(it, snap1 = presetSnap1, snap2 = presetSnap2) }
                            ?: showSnackbar("尚未載入底圖")
                    }
                    DxfSnapAction.BaselineP1 -> {
                        baselineA1 = BaselineAnchor("${typeLabel}(${String.format("%.3f", snap.worldE)},${String.format("%.3f", snap.worldN)})", snap.worldE, snap.worldN)
                        binding.canvasView.setBaseline(baselineA1, baselineA2)
                        showSnackbar(if (baselineA2 != null) "基準線已更新：P1=${baselineA1!!.name}" else "P1 已設定，請再點選基準線終點 (P2)")
                        invalidateOptionsMenu()
                    }
                    DxfSnapAction.BaselineP2 -> {
                        baselineA2 = BaselineAnchor("${typeLabel}(${String.format("%.3f", snap.worldE)},${String.format("%.3f", snap.worldN)})", snap.worldE, snap.worldN)
                        binding.canvasView.setBaseline(baselineA1, baselineA2)
                        showSnackbar(if (baselineA1 != null) "基準線已更新：P2=${baselineA2!!.name}" else "P2 已設定，請再點選基準線起點 (P1)")
                        invalidateOptionsMenu()
                    }
                    DxfSnapAction.ClearBaseline -> {
                        baselineA1 = null
                        baselineA2 = null
                        binding.canvasView.setBaseline(null as BaselineAnchor?, null)
                        invalidateOptionsMenu()
                        showSnackbar("基準線已清除")
                    }
                    DxfSnapAction.Dismiss -> dialog.dismiss()
                }
            }
            .show()
    }

    private fun snapToPointEntity(snap: DxfSnapPoint): PointEntity {
        val typeLabel = when (snap.type) {
            SnapType.ENDPOINT     -> "端點"
            SnapType.MIDPOINT     -> "中點"
            SnapType.CENTER       -> "圓心"
            SnapType.INTERSECTION -> "交點"
        }
        return PointEntity(
            id = 0,
            projectId = viewModel.currentProject.value?.id ?: 0L,
            pointName = snap.label.takeIf { it.isNotEmpty() && !it.startsWith("捕捉點") }
                ?: "CAD_${typeLabel}_${String.format("%.1f", snap.worldE).takeLast(4)}",
            easting = snap.worldE,
            northing = snap.worldN,
            elevation = 0.0,
            code = "CAD",
            horizontalAngle = null,
            verticalAngle = null,
            slopeDistance = null,
            horizontalDistance = null,
            verticalDistance = null,
            rawData = "",
            format = "CAD_SNAP",
            note = "CAD 捕捉點"
        )
    }

    private fun showDxfBatchStakeoutDialog() {
        val items = selectedDxfSnaps.map { "${it.label} (E:${String.format("%.2f", it.worldE)}, N:${String.format("%.2f", it.worldN)})" }.toTypedArray()
        val checked = BooleanArray(selectedDxfSnaps.size) { true }

        MaterialAlertDialogBuilder(this)
            .setTitle("CAD 批次放樣選取 (${selectedDxfSnaps.size} 點)")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("開始放樣") { _, _ ->
                val targets = selectedDxfSnaps.filterIndexed { index, _ -> checked[index] }
                    .map { snapToPointEntity(it) }
                if (targets.isEmpty()) {
                    showSnackbar("未選取任何點位")
                } else {
                    startStakeout(targets)
                }
            }
            .setNeutralButton("清空選取") { _, _ ->
                selectedDxfSnaps.clear()
                binding.canvasView.setSelectedSnapPoints(emptyList())
                invalidateOptionsMenu()
                showSnackbar("已清空選取清單")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBaselineListDialog(a1: BaselineAnchor, a2: BaselineAnchor) {
        val allPoints = viewModel.currentPoints.value
        data class Row(val name: String, val station: Double, val offset: Double, val elevation: Double?)
        val rows = allPoints.mapNotNull { pt ->
            SurveyMathUtils.calculateBaselineOffset(a1, a2, pt)?.let { res ->
                Row(pt.pointName, res.station, res.offset, pt.elevation)
            }
        }.sortedBy { it.station }

        if (rows.isEmpty()) {
            showSnackbar("目前無測量點可計算"); return
        }

        val baseLen = sqrt((a2.easting - a1.easting) * (a2.easting - a1.easting) +
                           (a2.northing - a1.northing) * (a2.northing - a1.northing))
        val header = buildString {
            append("基準線：${a1.name} → ${a2.name}\n")
            append("基線長：${String.format("%.3f", baseLen)} m\n\n")
            append(String.format("%-8s %10s %10s %8s\n", "點號", "里程(m)", "偏移(m)", "高程(m)"))
            append("─".repeat(42) + "\n")
        }
        val body = rows.joinToString("\n") { r ->
            String.format("%-8s %10.3f %+10.3f %8s",
                r.name, r.station, r.offset,
                r.elevation?.let { String.format("%.3f", it) } ?: "—")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("里程偏移表")
            .setMessage(header + body)
            .setPositiveButton("關閉", null)
            .setNeutralButton("複製") { _, _ ->
                val clip = android.content.ClipboardManager::class.java.let {
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                }
                clip.setPrimaryClip(android.content.ClipData.newPlainText("里程偏移表", header + body))
                showSnackbar("已複製到剪貼簿")
            }
            .show()
    }

    private fun showSnackbar(msg: String) {
        com.google.android.material.snackbar.Snackbar.make(
            binding.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun showDxfLayersDialog() {
        val data = currentDxfData ?: run { showSnackbar("尚未載入底圖"); return }
        val allLayers = data.entities.map { it.layer }.distinct().sorted()
        if (allLayers.isEmpty()) { showSnackbar("底圖中無圖層資訊"); return }

        val checkedItems = allLayers.map { binding.canvasView.visibleLayers.contains(it) }.toBooleanArray()
        
        MaterialAlertDialogBuilder(this)
            .setTitle("底圖圖層設定")
            .setMultiChoiceItems(allLayers.toTypedArray(), checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("確定") { _, _ ->
                val selectedLayers = allLayers.filterIndexed { index, _ -> checkedItems[index] }.toSet()
                binding.canvasView.setVisibleLayers(selectedLayers)
                showSnackbar("圖層設定已套用")
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("全選/全不選") { dialog, _ ->
                val anyUnchecked = checkedItems.any { !it }
                allLayers.indices.forEach { checkedItems[it] = anyUnchecked }
                (dialog as androidx.appcompat.app.AlertDialog).listView.let { lv ->
                    for (i in allLayers.indices) lv.setItemChecked(i, anyUnchecked)
                }
                // We don't dismiss here to allow the user to see the change
            }
            .show()
    }
}
