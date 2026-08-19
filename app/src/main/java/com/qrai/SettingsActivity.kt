package com.qrai

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class SettingsActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var apiListContainer: LinearLayout
    private lateinit var systemInput: EditText
    private var apis = mutableListOf<JSONObject>()
    private var activeIndex = 0

    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    /** Debug 模式开关：release 构建（非 debuggable）时自动关闭 */
    private val DEBUG: Boolean
        get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(24))
            }

        // ── 标题栏 ──
        val titleBar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        titleBar.addView(
            TextView(this).apply {
                text = "←"
                textSize = 22f
                setPadding(0, dp(8), dp(16), dp(8))
                setOnClickListener {
                    save()
                    finish()
                }
            },
        )
        titleBar.addView(
            TextView(this).apply {
                text = getString(R.string.settings_title)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF303030.toInt())
            },
        )
        root.addView(titleBar)

        // ── API 配置列表（独立限高滚动区） ──
        root.addView(label(getString(R.string.api_list_label)))
        val apiListScroll =
            ScrollView(this).apply {
                // 限高，让列表内部独立滚动，不挤压下方内容
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240))
                lp.topMargin = dp(4)
                layoutParams = lp
                isScrollbarFadingEnabled = false
            }
        apiListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        apiListScroll.addView(apiListContainer)
        root.addView(apiListScroll)

        root.addView(
            Button(this).apply {
                text = getString(R.string.api_add)
                textSize = 14f
                setTextColor(0xFF1A73E8.toInt())
                setBackgroundColor(Color.TRANSPARENT)
                elevation = 0f
                setOnClickListener { showAddApiDialog() }
            },
        )

        // ── System Prompt ──
        root.addView(label("System Prompt"))
        systemInput =
            EditText(this).apply {
                hint = getString(R.string.default_system_prompt)
                setText(prefs().getString("system", "") ?: "")
                textSize = 15f
                minLines = 2
                maxLines = 5
                setTextColor(0xFF303030.toInt())
                setHintTextColor(0xFFBBBBBB.toInt())
                background = getDrawable(R.drawable.input_bg)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                inputType = EditorInfo.TYPE_CLASS_TEXT
            }
        root.addView(systemInput)

        // ── 保存 ──
        root.addView(
            Button(this).apply {
                text = getString(R.string.btn_save)
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                background = getDrawable(R.drawable.btn_bg)
                elevation = 0f
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
                lp.topMargin = dp(28)
                layoutParams = lp
                setOnClickListener {
                    save()
                    Toast.makeText(this@SettingsActivity, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
                }
            },
        )

        scroll.addView(root)
        setContentView(scroll)
        loadApis()
    }

    private fun prefs() = getSharedPreferences("cfg", Context.MODE_PRIVATE)

    private fun loadApis() {
        apis.clear()
        val json = prefs().getString("apis", "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) apis.add(arr.getJSONObject(i))
        } catch (_: Exception) {
        }
        activeIndex = prefs().getInt("activeApi", 0).coerceIn(0, (apis.size - 1).coerceAtLeast(0))
        refreshApiList()

        // 调试：显示打开设置页时读到的配置（仅 Debug 构建）
        if (DEBUG) {
            try {
                AlertDialog
                    .Builder(this)
                    .setTitle(getString(R.string.debug_load_title))
                    .setMessage(getString(R.string.debug_load_msg, json, apis.size, activeIndex))
                    .setPositiveButton(getString(R.string.btn_close), null)
                    .show()
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshApiList() {
        apiListContainer.removeAllViews()
        if (apis.isEmpty()) {
            apiListContainer.addView(
                TextView(this).apply {
                    text = getString(R.string.api_list_empty)
                    textSize = 13f
                    setTextColor(0xFFBBBBBB.toInt())
                    setPadding(0, dp(8), 0, dp(8))
                },
            )
            return
        }
        apis.forEachIndexed { i, api ->
            val name = api.optString("name", "API ${i + 1}")
            val active = i == activeIndex
            val row =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(if (active) 0xFFE8F0FE.toInt() else 0xFFF5F5F5.toInt())
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    val lp =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    lp.bottomMargin = dp(6)
                    layoutParams = lp
                }
            // 圆圈图标 → 点击仅选中
            row.addView(
                TextView(this).apply {
                    text = if (active) "● " else "○ "
                    textSize = 16f
                    setTextColor(if (active) 0xFF1A73E8.toInt() else 0xFF999999.toInt())
                    setPadding(0, 0, dp(8), 0)
                    setOnClickListener {
                        activeIndex = i
                        refreshApiList()
                    }
                },
            )
            // 名称 → 点击编辑（也支持直接选中后再编辑）
            row.addView(
                TextView(this).apply {
                    text = name
                    textSize = 15f
                    setTextColor(0xFF303030.toInt())
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    layoutParams = lp
                    setOnClickListener { showAddApiDialog(i) }
                },
            )
            row.addView(
                TextView(this).apply {
                    text = api.optString("model", "")
                    textSize = 12f
                    setTextColor(0xFF999999.toInt())
                },
            )
            row.addView(
                TextView(this).apply {
                    text =
                        when (api.optString("endpoint", "chat")) {
                            "responses" -> " [R]"
                            "auto" -> " [A]"
                            else -> " [C]"
                        }
                    textSize = 10f
                    setTextColor(0xFF1A73E8.toInt())
                },
            )
            // 长按删除（整行均可触发）
            row.setOnLongClickListener {
                AlertDialog
                    .Builder(this)
                    .setTitle(getString(R.string.dialog_delete_title))
                    .setMessage(getString(R.string.dialog_delete_msg, name))
                    .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                        apis.removeAt(i)
                        if (activeIndex >= apis.size) activeIndex = (apis.size - 1).coerceAtLeast(0)
                        refreshApiList()
                    }.setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
                true
            }
            apiListContainer.addView(row)
        }
    }

    private fun showAddApiDialog(editIndex: Int = -1) {
        val ctx = this
        // 编辑模式：加载既有数据
        val editApi = if (editIndex in apis.indices) apis[editIndex] else null
        val isEdit = editApi != null

        // 弹窗内容包在可滚动 ScrollView 里，防止字段多时被按钮遮挡
        val contentScroll =
            ScrollView(ctx).apply {
                val lp =
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(420),
                    )
                layoutParams = lp
                isScrollbarFadingEnabled = false
            }
        val layout =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(16))
            }
        contentScroll.addView(layout)

        fun field(
            h: String,
            v: String,
            secret: Boolean = false,
            multi: Boolean = false,
        ) = EditText(ctx).apply {
            hint = h
            setText(v)
            textSize = 14f
            isSingleLine = !multi
            setTextColor(0xFF303030.toInt())
            setHintTextColor(0xFF999999.toInt())
            setBackgroundColor(0xFFF0F0F0.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            inputType =
                when {
                    secret -> EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                    multi -> EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
                    else -> EditorInfo.TYPE_CLASS_TEXT
                }
        }

        // ── 服务商预设 ──
        val presets =
            arrayOf(
                getString(R.string.preset_deepseek),
                getString(R.string.preset_openai),
                getString(R.string.preset_mimo),
                getString(R.string.preset_anthropic),
                getString(R.string.preset_kimi),
                getString(R.string.preset_glm),
                getString(R.string.preset_grok),
                getString(R.string.preset_gemini),
                getString(R.string.preset_custom),
            )
        val presetSpinner =
            Spinner(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
                background = getDrawable(R.drawable.spinner_bg)
            }
        presetSpinner.adapter =
            ArrayAdapter(ctx, android.R.layout.simple_spinner_item, presets).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        layout.addView(label(getString(R.string.label_provider_preset)))
        layout.addView(presetSpinner)

        val nameEt = field(getString(R.string.hint_name), editApi?.optString("name", "") ?: "DeepSeek")
        val keyEt = field(getString(R.string.label_api_key), editApi?.optString("key", "") ?: "", secret = true)
        val urlEt =
            field(
                getString(R.string.label_base_url),
                editApi?.optString("url", "") ?: "https://api.deepseek.com",
            )

        // ── 端点类型 ──
        val endpoints =
            arrayOf(
                getString(R.string.endpoint_chat),
                getString(R.string.endpoint_responses),
                getString(R.string.endpoint_auto),
            )
        val endpointSpinner =
            Spinner(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
                background = getDrawable(R.drawable.spinner_bg)
            }
        endpointSpinner.adapter =
            ArrayAdapter(ctx, android.R.layout.simple_spinner_item, endpoints).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        layout.addView(label(getString(R.string.label_endpoint)))
        layout.addView(endpointSpinner)

        // ── 联网搜索模板 ──
        val webJson =
            field(
                getString(R.string.hint_web_search),
                editApi?.optString("webSearch", "") ?: """{"tools":[{"type":"web_search"}],"tool_choice":"auto"}""",
                multi = true,
            ).apply {
                minLines = 2
                maxLines = 4
            }
        layout.addView(label(getString(R.string.label_web_search)))
        layout.addView(webJson)

        // ── 模型 Spinner + 获取按钮 ──
        val modelRow =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val modelSpinner =
            Spinner(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
                background = getDrawable(R.drawable.spinner_bg)
                // 初始隐藏，点"获取"成功后才显示（避免与手输框同时出现）
                visibility = android.view.View.GONE
            }
        val modelLoading =
            ProgressBar(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                visibility = android.view.View.GONE
            }
        val fetchBtn =
            TextView(ctx).apply {
                text = getString(R.string.btn_fetch)
                textSize = 13f
                setTextColor(0xFF1A73E8.toInt())
                setPadding(dp(12), dp(8), dp(4), dp(8))
            }
        modelRow.addView(modelSpinner)
        modelRow.addView(modelLoading)
        modelRow.addView(fetchBtn)

        layout.addView(label(getString(R.string.label_name)))
        layout.addView(nameEt)
        layout.addView(label(getString(R.string.label_api_key)))
        layout.addView(keyEt)
        layout.addView(label(getString(R.string.label_base_url)))
        layout.addView(urlEt)
        layout.addView(label(getString(R.string.label_model)))
        layout.addView(modelRow)

        // 手动输入兜底：编辑模式显示，新增模式隐藏
        val manualModel =
            field(
                getString(R.string.hint_manual_model),
                if (isEdit) editApi?.optString("model", "") ?: "" else "",
            ).apply {
                visibility = if (isEdit) android.view.View.VISIBLE else android.view.View.GONE
            }
        layout.addView(manualModel)

        // ── 预设填充逻辑 ──
        // Web search JSON 模板常量
        val wsAuto =
            """{"tools":[{"type":"web_search"}],"tool_choice":"auto"}"""
        val wsMimo =
            """{"tools":[{"type":"web_search","max_keyword":3,"force_search":true,"limit":1}],"tool_choice":"auto"}"""
        val wsAnthropic = """{"tools":[{"type":"web_search_20250305"}]}"""
        val wsGrok = """{"tools":[{"type":"web_search_preview"}],"tool_choice":"auto"}"""
        val wsGemini = """{"tools":[{"type":"googleSearch"}],"tool_choice":"auto"}"""

        fun applyPreset(index: Int) {
            var name = getString(R.string.preset_custom)
            var url = ""
            var endpointIdx = 0
            var web = ""
            when (index) {
                0 -> {
                    name = getString(R.string.preset_deepseek)
                    url = "https://api.deepseek.com"
                    endpointIdx = 1
                    web = wsAuto
                }

                1 -> {
                    name = getString(R.string.preset_openai)
                    url = "https://api.openai.com"
                    endpointIdx = 1
                    web = wsAuto
                }

                2 -> {
                    name = getString(R.string.preset_mimo)
                    url = "https://api.xiaomimimo.com/v1"
                    endpointIdx = 0
                    web = wsMimo
                }

                3 -> {
                    name = getString(R.string.preset_anthropic)
                    url = "https://api.anthropic.com"
                    endpointIdx = 0
                    web = wsAnthropic
                }

                4 -> {
                    name = getString(R.string.preset_kimi)
                    url = "https://api.moonshot.cn/v1"
                    endpointIdx = 0
                    web = wsAuto
                }

                5 -> {
                    name = getString(R.string.preset_glm)
                    url = "https://open.bigmodel.cn/api/paas/v4"
                    endpointIdx = 0
                    web = wsAuto
                }

                6 -> {
                    name = getString(R.string.preset_grok)
                    url = "https://api.x.ai"
                    endpointIdx = 1
                    web = wsGrok
                }

                7 -> {
                    name = getString(R.string.preset_gemini)
                    url = "https://generativelanguage.googleapis.com/v1beta/openai"
                    endpointIdx = 0
                    web = wsGemini
                }
            }
            nameEt.setText(name)
            urlEt.setText(url)
            endpointSpinner.setSelection(endpointIdx)
            webJson.setText(web)
            if (!isEdit) manualModel.visibility = android.view.View.GONE
        }
        // 预设下拉保持可用（编辑模式也允许用预设快速填充）。
        // 关键：setSelection 的回调可能异步且多次触发，把编辑预填值覆盖成默认。
        // 方案：监听器只响应用户真正改变选择（position 变化），初始 setSelection 用
        //       setSelection(pos, false) 且把初始回调记录为"当前已应用位置"，不再 applyPreset。
        var lastPresetPos = -1
        presetSpinner.isEnabled = true
        presetSpinner.alpha = 1f
        presetSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long,
                ) {
                    // 只有位置变化才应用（用户手动选择）
                    if (lastPresetPos != position) {
                        lastPresetPos = position
                        applyPreset(position)
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        if (isEdit) {
            // 编辑模式：选中"自定义"（index 3）但不触发 applyPreset，保留预填值
            lastPresetPos = 3
            presetSpinner.setSelection(8, false)
            // 确保预填值不被任何初始化覆盖：显式重设一次
            nameEt.setText(editApi?.optString("name", "") ?: "")
            urlEt.setText(editApi?.optString("url", "") ?: "")
            webJson.setText(editApi?.optString("webSearch", "") ?: "")
            manualModel.setText(editApi?.optString("model", "") ?: "")
            val ep = editApi?.optString("endpoint", "chat") ?: "chat"
            endpointSpinner.setSelection(
                when (ep) {
                    "responses" -> 1
                    "auto" -> 2
                    else -> 0
                },
            )
        } else {
            presetSpinner.setSelection(8, false)
        }

        // 模型列表（在 dialog 之前声明，供保存按钮 lambda 捕获）
        var modelList = mutableListOf<String>()

        val dialog =
            AlertDialog
                .Builder(ctx)
                .setTitle(
                    if (isEdit) {
                        getString(R.string.dialog_edit_title)
                    } else {
                        getString(R.string.dialog_add_title)
                    },
                ).setView(contentScroll)
                .setPositiveButton(
                    if (isEdit) {
                        getString(R.string.btn_save_apply)
                    } else {
                        getString(R.string.btn_add)
                    },
                    null,
                ).setNegativeButton(getString(R.string.btn_cancel), null)
                .create()

        // 在对话框显示后绑定正面按钮逻辑
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // 优先取手输框，其次 Spinner，最后回退原值
                val manual = manualModel.text.toString().trim()
                val spinnerVisible =
                    modelSpinner.visibility == android.view.View.VISIBLE
                val spinnerVal =
                    if (spinnerVisible && modelList.isNotEmpty()) {
                        modelSpinner.selectedItem?.toString() ?: ""
                    } else {
                        ""
                    }
                val selectedModel =
                    manual
                        .ifEmpty { spinnerVal }
                        .ifEmpty {
                            if (isEdit) (editApi?.optString("model", "") ?: "") else ""
                        }
                if (selectedModel.isEmpty()) {
                    Toast.makeText(ctx, getString(R.string.toast_select_model), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (selectedModel.contains("reason", ignoreCase = true)) {
                    Toast.makeText(ctx, getString(R.string.toast_reason_banned), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val web = webJson.text.toString().trim()
                if (web.isNotEmpty()) {
                    try {
                        JSONObject(web)
                    } catch (e: Exception) {
                        Toast.makeText(ctx, getString(R.string.toast_web_json_error), Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                }
                val endpoint =
                    when (endpointSpinner.selectedItemPosition) {
                        1 -> "responses"
                        2 -> "auto"
                        else -> "chat"
                    }
                if (isEdit) {
                    editApi!!.put(
                        "name",
                        nameEt.text
                            .toString()
                            .trim()
                            .ifEmpty { "API ${editIndex + 1}" },
                    )
                    editApi.put("key", keyEt.text.toString().trim())
                    editApi.put(
                        "url",
                        urlEt.text
                            .toString()
                            .trim()
                            .ifEmpty { "https://api.deepseek.com" },
                    )
                    editApi.put("model", selectedModel)
                    editApi.put("endpoint", endpoint)
                    editApi.put("webSearch", web)
                } else {
                    val api =
                        JSONObject().apply {
                            put(
                                "name",
                                nameEt.text
                                    .toString()
                                    .trim()
                                    .ifEmpty { "API ${apis.size + 1}" },
                            )
                            put("key", keyEt.text.toString().trim())
                            put(
                                "url",
                                urlEt.text
                                    .toString()
                                    .trim()
                                    .ifEmpty { "https://api.deepseek.com" },
                            )
                            put("model", selectedModel)
                            put("endpoint", endpoint)
                            put("webSearch", web)
                        }
                    apis.add(api)
                    activeIndex = apis.size - 1
                }
                refreshApiList()

                // 调试：保存前确认 editApi 状态（仅 Debug 构建）
                if (DEBUG) {
                    try {
                        AlertDialog
                            .Builder(ctx)
                            .setTitle(getString(R.string.debug_save_btn_title))
                            .setMessage(
                                getString(
                                    R.string.debug_save_btn_msg,
                                    isEdit,
                                    editIndex,
                                    selectedModel,
                                    editApi == null,
                                    editApi?.toString() ?: "null",
                                ),
                            ).setPositiveButton(getString(R.string.btn_close), null)
                            .show()
                    } catch (_: Exception) {
                    }
                }

                save()
                Toast
                    .makeText(
                        ctx,
                        getString(R.string.toast_saved_config),
                        Toast.LENGTH_SHORT,
                    ).show()
                dialog.dismiss()
            }
        }

        dialog.show()

        // 获取模型列表
        fun doFetch() {
            val k = keyEt.text.toString().trim()
            val u =
                urlEt.text
                    .toString()
                    .trim()
                    .ifEmpty { "https://api.deepseek.com" }
            if (k.isEmpty()) {
                Toast
                    .makeText(
                        ctx,
                        getString(R.string.toast_need_api_key),
                        Toast.LENGTH_SHORT,
                    ).show()
                return
            }
            fetchBtn.visibility = android.view.View.GONE
            modelLoading.visibility = android.view.View.VISIBLE

            exec.execute {
                var conn: HttpURLConnection? = null
                try {
                    val url = URL(apiUrl(u, "/models"))
                    conn =
                        (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            setRequestProperty("Authorization", "Bearer $k")
                            connectTimeout = 15_000
                            readTimeout = 15_000
                        }
                    if (conn.responseCode == 200) {
                        val body =
                            BufferedReader(
                                InputStreamReader(conn.inputStream),
                            ).use { it.readText() }
                        val json = JSONObject(body)
                        val data = json.getJSONArray("data")
                        modelList.clear()
                        for (i in 0 until data.length()) {
                            val id = data.getJSONObject(i).optString("id", "")
                            // 剔除旧版推理模型名
                            if (id.isEmpty() ||
                                id.contains("reason", ignoreCase = true)
                            ) {
                                continue
                            }
                            modelList.add(id)
                        }
                        modelList.sort()
                        ui.post {
                            modelLoading.visibility = android.view.View.GONE
                            if (modelList.isEmpty()) {
                                Toast
                                    .makeText(
                                        ctx,
                                        getString(R.string.toast_no_models),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                fetchBtn.visibility = android.view.View.VISIBLE
                            } else {
                                val adapter =
                                    ArrayAdapter(
                                        ctx,
                                        android.R.layout.simple_spinner_item,
                                        modelList,
                                    )
                                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                                modelSpinner.adapter = adapter
                                modelSpinner.visibility = android.view.View.VISIBLE
                                // 二选一：有列表时隐藏手输框
                                manualModel.visibility = android.view.View.GONE
                                Toast
                                    .makeText(
                                        ctx,
                                        getString(
                                            R.string.toast_models_fetched,
                                            modelList.size,
                                        ),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        }
                    } else {
                        ui.post {
                            modelLoading.visibility = android.view.View.GONE
                            fetchBtn.visibility = android.view.View.VISIBLE
                            manualModel.visibility = android.view.View.VISIBLE
                            Toast
                                .makeText(
                                    ctx,
                                    getString(
                                        R.string.toast_fetch_failed,
                                        conn.responseCode,
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                    }
                } catch (e: Exception) {
                    ui.post {
                        modelLoading.visibility = android.view.View.GONE
                        fetchBtn.visibility = android.view.View.VISIBLE
                        manualModel.visibility = android.view.View.VISIBLE
                        Toast
                            .makeText(
                                ctx,
                                getString(
                                    R.string.toast_network_error,
                                    e.message ?: "",
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                } finally {
                    conn?.disconnect()
                }
            }
        }

        fetchBtn.setOnClickListener { doFetch() }

        // 也允许输入 URL/Key 后自动触发（用户改了 URL 就重置）
        urlEt.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && modelList.isEmpty()) {
                // 失去焦点时如果列表为空，可以不自动获取，等用户点按钮
            }
        }
    } // end showAddApiDialog

    private fun save() {
        val arr = JSONArray()
        apis.forEach { arr.put(it) }

        val editor = prefs().edit()
        editor.putString("apis", arr.toString())
        editor.putInt("activeApi", activeIndex)
        editor.putString("system", systemInput.text.toString().trim())
        val active = apis.getOrNull(activeIndex)
        if (active != null) {
            editor.putString("key", active.optString("key", ""))
            editor.putString("url", active.optString("url", ""))
            editor.putString("model", active.optString("model", ""))
        }
        editor.apply()

        // 调试：弹完整对话框显示日志（仅 Debug 构建）
        if (DEBUG) {
            try {
                AlertDialog
                    .Builder(this)
                    .setTitle(getString(R.string.debug_save_title))
                    .setMessage(
                        getString(
                            R.string.debug_save_msg,
                            apis.size,
                            activeIndex,
                            arr.toString(),
                            arr.toString().length,
                        ),
                    ).setPositiveButton(getString(R.string.btn_close), null)
                    .show()
            } catch (_: Exception) {
            }
        }
    }

    private fun label(s: String) =
        TextView(this).apply {
            text = s
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(20), 0, dp(6))
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        /** 智能拼接 API URL：处理 base 已带 /v1 的情况，避免 /v1/v1 */
        fun apiUrl(
            base: String,
            path: String,
        ): String {
            val b = base.trimEnd('/')
            return if (b.endsWith("/v1")) "$b$path" else "$b/v1$path"
        }
    }
}
