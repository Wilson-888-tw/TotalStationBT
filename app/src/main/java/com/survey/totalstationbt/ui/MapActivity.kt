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

class MapActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMapBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var pointAdapter: PointCheckAdapter
    
    private val visiblePointIds = mutableSetOf<Long>()
    private var stakeoutTarget: PointEntity? = null
    private var elevationBase: PointEntity? = null
    private var baselineP1: PointEntity? = null
    private var baselineP2: PointEntity? = null
    private var tts: TextToSpeech? = null
    private lateinit var stakeoutSheet: BottomSheetBehavior<*>

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
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupListeners() {
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

        binding.btnStopStakeout.setOnClickListener {
            stakeoutTarget = null
            baselineP1 = null
            baselineP2 = null
            binding.canvasView.setBaseline(null, null)
            stakeoutSheet.state = BottomSheetBehavior.STATE_HIDDEN
            binding.canvasView.clearSelection()
        }
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
                append("座標：E=${String.format("%.3f", selected[0].easting)}, N=${String.format("%.3f", selected[0].northing)}\n")
                append("高程：Z=${String.format("%.3f", selected[0].elevation)}")
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
                
                if (baselineP1?.id == p1.id && baselineP2?.id == p2.id) {
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
        
        if (elevationBase != null || baselineP1 != null) {
            builder.setNeutralButton("取消基準/基線") { _, _ ->
                elevationBase = null
                baselineP1 = null
                baselineP2 = null
                binding.canvasView.setElevationBase(null)
                binding.canvasView.setBaseline(null, null)
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
                startStakeout(selected[0])
            }
        } else if (selected.size == 2) {
            builder.setPositiveButton("設為基準線") { _, _ ->
                baselineP1 = selected[0]
                baselineP2 = selected[1]
                binding.canvasView.setBaseline(baselineP1, baselineP2)
                showSnackbar("基準線已設定：${baselineP1!!.pointName} -> ${baselineP2!!.pointName}")
            }
            builder.setNegativeButton("顯示剖面圖") { _, _ ->
                showProfileDialog(selected)
            }
        } else if (selected.size >= 3) {
            builder.setNegativeButton("顯示剖面圖") { _, _ ->
                showProfileDialog(selected)
            }
        }
        
        builder.show()
    }

    private fun startStakeout(target: PointEntity) {
        stakeoutTarget = target
        binding.tvStakeoutTarget.text = "正在放樣：${target.pointName}"
        stakeoutSheet.state = BottomSheetBehavior.STATE_EXPANDED
        showSnackbar("已進入放樣模式，等待最新測量值…")
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
        val elevationOnly = binding.checkElevationOnly.isChecked
        
        binding.tvDistDelta.text = "距離目標：${String.format("%.3f", guidance.distance2D)} m"
        binding.tvHeightDelta.text = "填挖：${String.format("%+.3f", guidance.deltaZ)} m"
        
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
        } else {
            binding.tvDistDelta.setTextColor(getColor(R.color.status_connecting))
            binding.imgDirection.setColorFilter(getColor(R.color.status_connecting))
        }

        // 語音導引 (每 5 秒播報一次，或是當距離變化大時)
        // 這裡先簡單實現，之後可以加頻率控制
        tts?.speak(StakeoutCalculator.getVoiceCommand(guidance, elevationOnly), TextToSpeech.QUEUE_FLUSH, null, null)
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

    private fun showSnackbar(msg: String) {
        com.google.android.material.snackbar.Snackbar.make(
            binding.root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
        ).show()
    }
}
