package com.qrai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var messageList: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var statusBarSpacer: View
    private lateinit var settingsBtn: TextView
    private lateinit var modelSpinner: Spinner
    private lateinit var netBtn: TextView
    private lateinit var inputPanel: LinearLayout
    private lateinit var rootLayout: android.widget.FrameLayout
    private lateinit var thinkLabel: TextView
    private lateinit var thinkBar: android.widget.SeekBar
    private lateinit var thinkVal: TextView

    private val history = mutableListOf<Pair<String, String>>()
    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var busy = false

    // 当前正在流式输出的 AI 消息（气泡容器 + 内部文本视图）
    private var currentAiView: LinearLayout? = null
    private var currentAiText: TextView? = null

    // "请先设置 API"提示气泡引用（配置好 API 后移除）
    private var setupHintView: LinearLayout? = null

    // ── config ──
    private var apiKey = ""
    private var baseUrl = "https://api.deepseek.com"
    private var model = "deepseek-v4-flash"
    private var systemPrompt = ""
    private var endpoint = "chat" // chat | responses | auto
    private var webSearchJson = "" // 联网模板（可空 = 该 API 不支持联网）

    // 思考模式: 0=关闭, 1=低, 2=中, 3=高, 4=最大
    private var thinkLevel = 0

    // 上滑清屏提示
    private var clearHint: TextView? = null

    // 上滑清屏：滚到底部后向上滑动超阈值触发，带冷却防抖
    private var wasAtBottom = true
    private var lastClearTime = 0L
    private var clearThreshold = 300 // dp 值，onCreate 里转为像素
    private val clearCooldown = 1000L // 1秒冷却

    // APIs list from settings
    private var apis = mutableListOf<JSONObject>()
    private var activeApiIndex = 0

    // 联网模式: 0=不联网, 1=联网(强制搜索), 2=自动(模型自主决定)
    // 注意：getString() 需在 onCreate 后调用（Context 就绪），不能用类字段初始化
    private lateinit var netModes: Array<String>
    private lateinit var netLabels: Array<String>
    private var netMode = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 联网模式字符串（需 onCreate 后 Context 就绪再初始化）
        netModes =
            arrayOf(
                getString(R.string.net_off),
                getString(R.string.net_on),
                getString(R.string.net_auto),
            )
        netLabels =
            arrayOf(
                getString(R.string.net_off_label),
                getString(R.string.net_on_label),
                getString(R.string.net_auto_label),
            )

        messageList = findViewById(R.id.messageList)
        scroll = findViewById(R.id.scroll)
        rootLayout = findViewById(R.id.rootLayout)
        input = findViewById(R.id.input)
        sendBtn = findViewById(R.id.sendBtn)
        statusBarSpacer = findViewById(R.id.statusBarSpacer)
        settingsBtn = findViewById(R.id.settingsBtn)
        modelSpinner = findViewById(R.id.modelSpinner)
        netBtn = findViewById(R.id.netBtn)
        inputPanel = findViewById(R.id.inputPanel)
        thinkLabel = findViewById(R.id.thinkLabel)
        thinkBar = findViewById(R.id.thinkBar)
        thinkVal = findViewById(R.id.thinkVal)

        clearThreshold = dp(300) // 在 onCreate 里转为像素（需 Context 就绪）

        // 状态栏高度占位
        statusBarSpacer.post {
            val res = resources
            val statusBarId = res.getIdentifier("status_bar_height", "dimen", "android")
            val height = if (statusBarId > 0) res.getDimensionPixelSize(statusBarId) else dp(24)
            statusBarSpacer.layoutParams.height = height
            statusBarSpacer.requestLayout()
        }

        // 思考模式滑动条
        thinkLevel = getSharedPreferences("cfg", Context.MODE_PRIVATE).getInt("thinkLevel", 0)
        thinkBar.progress = thinkLevel
        refreshThinkLabel()
        thinkBar.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: android.widget.SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    thinkLevel = progress
                    refreshThinkLabel()
                    if (fromUser) {
                        getSharedPreferences("cfg", MODE_PRIVATE).edit().putInt("thinkLevel", thinkLevel).apply()
                    }
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            },
        )

        sendBtn.backgroundTintList = null

        sendBtn.setOnClickListener { send() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }

        // 键盘处理：adjustNothing，根容器按 IME insets 加底部 padding 让出键盘空间。
        // 这样聊天区和浮动输入面板整体上移，输入框浮在键盘上方，且无多余空白。
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.decorView.setOnApplyWindowInsetsListener { v, insets ->
                val imeH =
                    insets
                        .getInsets(
                            android.view.WindowInsets.Type
                                .ime(),
                        ).bottom
                if (rootLayout.paddingBottom != imeH) {
                    rootLayout.setPadding(0, 0, 0, imeH)
                }
                if (imeH > 0) {
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
                v.onApplyWindowInsets(insets)
            }
        } else {
            // API < 30：用 visible display frame 兜底
            scroll.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val r = Rect()
                        scroll.getWindowVisibleDisplayFrame(r)
                        val rootH = scroll.rootView.height
                        val keyH = if (rootH - r.bottom > 150) rootH - r.bottom else 0
                        if (rootLayout.paddingBottom != keyH) {
                            rootLayout.setPadding(0, 0, 0, keyH)
                        }
                        if (keyH > 0) {
                            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                        }
                    }
                },
            )
        }

        // 上滑清屏：在底部时快速上滑，清空聊天区
        scroll.setOnScrollChangeListener(
            View.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                val maxScroll = (messageList.height - scroll.height).coerceAtLeast(0)
                val atBottom = maxScroll <= 0 || scrollY >= maxScroll - dp(16)
                if (wasAtBottom && oldScrollY > 0 && oldScrollY > scrollY) {
                    val now = System.currentTimeMillis()
                    if (messageList.childCount > 1 && now - lastClearTime > clearCooldown) {
                        lastClearTime = now
                        messageList.removeAllViews()
                        setupHintView = null
                        currentAiView = null
                        currentAiText = null
                        Toast.makeText(this@MainActivity, "已清屏", Toast.LENGTH_SHORT).show()
                    }
                }
                wasAtBottom = atBottom
            },
        )

        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 联网按钮循环切换
        netBtn.setOnClickListener {
            netMode = (netMode + 1) % 3
            refreshNetBtn()
        }

        // 先加载配置，再决定是否显示"请先设置 API"提示（避免 Loading 闪烁）
        loadConfig()
        if (apiKey.isEmpty()) {
            setupHintView = addMessage(getString(R.string.msg_setup_hint), isUser = false)
        }
        updateChatBottomPadding()
    }

    override fun onResume() {
        super.onResume()
        loadConfig()
        refreshModelSpinner()
        refreshNetBtn()
        updateSetupHint()
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)

        // 加载 APIs 列表
        apis.clear()
        val json = prefs.getString("apis", "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) apis.add(arr.getJSONObject(i))
        } catch (_: Exception) {
        }

        activeApiIndex = prefs.getInt("activeApi", 0).coerceIn(0, (apis.size - 1).coerceAtLeast(0))

        // 选中的 API
        val active = apis.getOrNull(activeApiIndex)
        apiKey = active?.optString("key", "") ?: prefs.getString("key", "") ?: ""
        baseUrl = active?.optString("url", "")?.ifEmpty { "https://api.deepseek.com" }
            ?: prefs.getString("url", "")?.ifEmpty { "https://api.deepseek.com" } ?: "https://api.deepseek.com"
        model = active?.optString("model", "")?.ifEmpty { "deepseek-v4-flash" }
            ?: prefs.getString("model", "")?.ifEmpty { "deepseek-v4-flash" } ?: "deepseek-v4-flash"
        endpoint = active?.optString("endpoint", "chat")?.ifEmpty { "chat" } ?: "chat"
        webSearchJson = active?.optString("webSearch", "") ?: ""
        // 快问快答：兜底过滤旧版推理模型（V4 时代思考由 thinking 参数控制）
        if (model.contains("reason", ignoreCase = true)) model = "deepseek-v4-flash"
        systemPrompt = prefs.getString("system", "")?.ifEmpty { getString(R.string.default_system_prompt) }
            ?: getString(R.string.default_system_prompt)
    }

    private fun refreshModelSpinner() {
        val modelNames =
            if (apis.isEmpty()) {
                listOf(getString(R.string.msg_no_api))
            } else {
                apis.map { api ->
                    val name = api.optString("name", "")
                    val m = api.optString("model", "")
                    if (name.isNotEmpty() && m.isNotEmpty()) {
                        "$name · $m"
                    } else if (name.isNotEmpty()) {
                        name
                    } else {
                        m
                    }
                }
            }

        val adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, modelNames).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        modelSpinner.adapter = adapter

        // 选中当前 active
        if (apis.isNotEmpty()) {
            modelSpinner.setSelection(activeApiIndex.coerceIn(0, modelNames.size - 1))
        }

        modelSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    if (position != activeApiIndex && position < apis.size) {
                        activeApiIndex = position
                        val api = apis[activeApiIndex]
                        apiKey = api.optString("key", "")
                        baseUrl = api.optString("url", "").ifEmpty { "https://api.deepseek.com" }
                        model = api.optString("model", "").ifEmpty { "deepseek-v4-flash" }
                        endpoint = api.optString("endpoint", "chat").ifEmpty { "chat" }
                        webSearchJson = api.optString("webSearch", "")
                        // 快问快答：兜底过滤旧版推理模型
                        if (model.contains("reason", ignoreCase = true)) model = "deepseek-v4-flash"
                        getSharedPreferences("cfg", MODE_PRIVATE).edit().putInt("activeApi", activeApiIndex).apply()
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    private fun refreshNetBtn() {
        netBtn.text = netLabels[netMode]
        netBtn.setTextColor(
            when (netMode) {
                0 -> 0xFFE53935.toInt()

                // 红色
                1 -> 0xFF43A047.toInt()

                // 绿色
                else -> 0xFF1A73E8.toInt() // 蓝色
            },
        )
    }

    /** 刷新思考模式标签文字 */
    private fun refreshThinkLabel() {
        val names =
            arrayOf(
                getString(R.string.think_off),
                getString(R.string.think_low),
                getString(R.string.think_medium),
                getString(R.string.think_high),
                getString(R.string.think_max),
            )
        thinkVal.text = names[thinkLevel.coerceIn(0, 4)]
        thinkVal.setTextColor(if (thinkLevel == 0) 0xFF999999.toInt() else 0xFF1A73E8.toInt())
    }

    /** 按思考等级拼接 Chat Completions 思考参数 */
    private fun thinkingChatParam(): JSONObject = JSONObject().put("type", if (thinkLevel == 0) "disabled" else "enabled")

    /** 按思考等级拼接 Responses API 思考参数 */
    private fun reasoningParam(): JSONObject {
        // 0=none(关闭), 1=low, 2=medium→high, 3=high, 4=max
        return JSONObject().put(
            "effort",
            when (thinkLevel) {
                0 -> "none"
                1 -> "low"
                2 -> "medium"
                3 -> "high"
                else -> "max"
            },
        )
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 本次请求是否注入联网搜索：0=不联网 1=强制 2=自动(有网才注入，由模型自主决定) */
    private fun webSearchForce(): Boolean? =
        when (netMode) {
            0 -> null

            // 不联网
            1 -> true

            // 强制联网搜索
            else -> if (isNetworkAvailable()) false else null // 自动：有网→注入(自主)，无网→不注入
        }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        if (apiKey.isEmpty()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        when (text.lowercase()) {
            "/clear" -> {
                history.clear()
                messageList.removeAllViews()
                currentAiView = null
                currentAiText = null
                input.text.clear()
                return
            }

            "/quit", "/exit" -> {
                finish()
                return
            }
        }

        busy = true
        sendBtn.alpha = 0.4f
        input.text.clear()
        hideKeyboard()

        // 用户消息（圆角框）
        addMessage(text, isUser = true)
        history.add("user" to text)

        // 问答之间插一条分隔线
        addDivider()

        // AI 回复（圆角框，流式更新）
        currentAiView = addMessage("", isUser = false)

        exec.execute { callApi() }
    }

    private fun callApi() {
        // 每个请求前清空解析缓冲
        chatSb.setLength(0)
        respSb.setLength(0)
        chatThinkSb.setLength(0)
        respThinkSb.setLength(0)

        // 根据配置的端点路由
        val useResponses =
            when (endpoint) {
                "responses" -> true
                "chat" -> false
                else -> true
            }
        if (useResponses) {
            val ok = postStream("/responses", ::buildResponsesBody, ::parseResponsesEvent)
            if (ok) {
                history.add("assistant" to respSb.toString())
            } else if (endpoint == "auto") {
                // 回退到 chat 端点
                respSb.setLength(0)
                val ok2 = postStream("/chat/completions", ::buildChatBody, ::parseChatEvent)
                if (ok2) history.add("assistant" to chatSb.toString())
            }
        } else {
            val ok = postStream("/chat/completions", ::buildChatBody, ::parseChatEvent)
            if (ok) history.add("assistant" to chatSb.toString())
        }
    }

    /** 构造 Chat Completions 请求体 */
    private fun buildChatBody(body: JSONObject) {
        body.put("model", model)
        body.put("stream", true)
        // 思考等级：0=disabled，1-4=enabled + 对应 effort（Chat 用 thinking 参数）
        body.put("thinking", thinkingChatParam())
        if (thinkLevel > 0) {
            body.put(
                "reasoning_effort",
                when (thinkLevel) {
                    1 -> "low"
                    2 -> "medium"
                    3 -> "high"
                    else -> "max"
                },
            )
        }
        body.put(
            "messages",
            JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                history.forEach { (role, content) ->
                    put(JSONObject().put("role", role).put("content", content))
                }
            },
        )
        // 联网搜索注入
        val force = webSearchForce() ?: return
        injectWebSearch(body, force)
    }

    /** 构造 Responses API 请求体（instructions + input items 格式） */
    private fun buildResponsesBody(body: JSONObject) {
        body.put("model", model)
        body.put("stream", true)
        // 思考等级：0=none(关闭)，1-4=对应 effort（Responses 用 reasoning 参数）
        body.put("reasoning", reasoningParam())
        // instructions 作为第一条 system 消息（Responses API 规范）
        body.put("instructions", systemPrompt)
        body.put(
            "input",
            JSONArray().apply {
                history.forEach { (role, content) ->
                    put(JSONObject().put("role", role).put("content", content))
                }
            },
        )
        // 联网搜索注入
        val force = webSearchForce() ?: return
        injectWebSearch(body, force)
    }

    /** 把联网模板合并进请求体；force=true 强制搜索，false 交给模型自主决定 */
    private fun injectWebSearch(
        body: JSONObject,
        force: Boolean,
    ) {
        val template = webSearchJson.trim()
        if (template.isEmpty()) {
            ui.post { updateAiText(getString(R.string.msg_no_web_template)) }
            return
        }
        try {
            val extra = JSONObject(template)
            val tools = extra.optJSONArray("tools")
            if (tools != null) {
                // 根据 force 调整每个 web_search 工具的 force_search
                for (i in 0 until tools.length()) {
                    val tool = tools.optJSONObject(i) ?: continue
                    if (tool.optString("type") == "web_search") {
                        tool.put("force_search", force)
                    }
                }
                body.put("tools", tools)
            }
            val tc = extra.optString("tool_choice", "")
            body.put("tool_choice", if (force) "required" else (tc.ifEmpty { "auto" }))
        } catch (e: Exception) {
            ui.post { updateAiText(getString(R.string.msg_web_template_error, e.message ?: "")) }
        }
    }

    /** 发送流式 POST 请求，用 parser 逐行解析 SSE，返回是否成功 */
    private fun postStream(
        path: String,
        buildBody: (JSONObject) -> Unit,
        parser: (String) -> Unit,
    ): Boolean {
        var conn: HttpURLConnection? = null
        var success = false
        try {
            val body = JSONObject()
            buildBody(body)

            val url = URL(SettingsActivity.apiUrl(baseUrl, path))
            val isAnthropic = webSearchJson.contains("web_search_20250305")
            conn =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    if (isAnthropic) {
                        setRequestProperty("x-api-key", apiKey)
                        setRequestProperty("anthropic-version", "2023-06-01")
                    } else {
                        setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    doOutput = true
                }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            if (conn.responseCode != 200) {
                val err = BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
                ui.post {
                    updateAiText(getString(R.string.msg_error, "${conn.responseCode} ($path): ${err.take(200)}"))
                    currentAiText?.setTextColor(0xFFD32F2F.toInt())
                    finishTurn()
                }
                return false
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            var done = false

            while (!done) {
                val line = reader.readLine() ?: break
                // 兼容 SSE 的 \r\n 行尾
                val data = line.removePrefix("data:").trim().trim('\r')
                if (data.isEmpty()) continue
                if (data.startsWith("[DONE]")) break
                // Responses API 结束事件
                if (data.contains("\"type\":\"response.completed\"") ||
                    data.contains("\"type\": \"response.completed\"") ||
                    data.contains("\"type\":\"response.failed\"") ||
                    data.contains("\"type\":\"response.incomplete\"")
                ) {
                    done = true
                }
                try {
                    parser(data)
                } catch (_: Exception) {
                }
            }

            ui.post {
                finishTurn()
            }
            success = true
        } catch (e: Exception) {
            ui.post {
                updateAiText(getString(R.string.msg_error, e.message ?: ""))
                currentAiText?.setTextColor(0xFFD32F2F.toInt())
                finishTurn()
            }
        } finally {
            conn?.disconnect()
        }
        return success
    }

    /** 解析 Chat Completions SSE 行 */
    private val chatSb = StringBuilder()
    private val chatThinkSb = StringBuilder()

    private fun parseChatEvent(data: String) {
        val chunk = JSONObject(data)
        val choices = chunk.optJSONArray("choices") ?: return
        if (choices.length() == 0) return
        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return
        val reasoning = delta.optString("reasoning_content", "")
        val content = delta.optString("content", "")

        if (reasoning.isNotEmpty()) {
            chatThinkSb.append(reasoning)
            val snapshot = chatThinkSb.toString()
            ui.post { updateAiText("· " + snapshot.replace("\n", "\n· ")) }
        }
        if (content.isNotEmpty()) {
            chatSb.append(content)
            val snapshot = chatSb.toString()
            ui.post { updateAiText(snapshot) }
        }
    }

    /** 解析 Responses API SSE 事件行 */
    private val respSb = StringBuilder()
    private val respThinkSb = StringBuilder()

    private fun parseResponsesEvent(data: String) {
        val evt = JSONObject(data)
        when (evt.optString("type", "")) {
            "response.output_text.delta" -> {
                val delta = evt.optString("delta", "")
                if (delta.isNotEmpty()) {
                    respSb.append(delta)
                    val snapshot = respSb.toString()
                    ui.post { updateAiText(snapshot) }
                }
            }

            "response.reasoning_text.delta" -> {
                val delta = evt.optString("delta", "")
                if (delta.isNotEmpty()) {
                    respThinkSb.append(delta)
                    val snapshot = respThinkSb.toString()
                    ui.post { updateAiText("· " + snapshot.replace("\n", "\n· ")) }
                }
            }

            "response.web_search_call.in_progress",
            "response.web_search_call.searching",
            -> {
                ui.post { updateAiText(getString(R.string.msg_searching)) }
            }
        }
    }

    /** 添加一条消息气泡（圆角框容器，内含文本/表格） */
    private fun addMessage(
        text: String,
        isUser: Boolean,
    ): LinearLayout {
        // 气泡容器：带圆角背景，垂直排列（文本 + 表格）
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(if (isUser) R.drawable.bubble_user else R.drawable.bubble_ai)
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
        // 文本子视图
        val tv =
            TextView(this).apply {
                this.text = text
                textSize = 15f
                setLineSpacing(0f, 1.3f)
                setTextColor(if (isUser) 0xFF1B5E20.toInt() else 0xFF303030.toInt())
            }
        container.addView(tv)
        if (!isUser) currentAiText = tv

        val lp =
            LinearLayout.LayoutParams(
                if (isUser) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        if (isUser) lp.gravity = android.view.Gravity.END
        lp.topMargin = dp(6)
        lp.bottomMargin = dp(6)
        messageList.addView(container, lp)
        ensureClearHint()
        updateChatBottomPadding()
        scrollToBottom()
        return container
    }

    /** 问答之间插入一条竖线分隔 */
    private fun addDivider() {
        val line =
            View(this).apply {
                setBackgroundResource(R.drawable.divider_line)
            }
        val lp =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1),
            )
        lp.topMargin = dp(6)
        lp.bottomMargin = dp(6)
        messageList.addView(line, lp)
    }

    /** 更新当前 AI 气泡内容（流式，显示明文） */
    private fun updateAiText(text: String) {
        currentAiText?.text = text
        scrollToBottom()
    }

    private fun scrollToBottom() {
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** 确保"上滑清除"提示在消息列表最末尾 */
    private fun ensureClearHint() {
        if (clearHint == null) {
            clearHint =
                TextView(this).apply {
                    text = getString(R.string.swipe_clear_hint)
                    textSize = 11f
                    setTextColor(0xFFBBBBBB.toInt())
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(4))
                }
        }
        val hint = clearHint!!
        if (hint.parent != null) {
            (hint.parent as? android.view.ViewGroup)?.removeView(hint)
        }
        messageList.addView(hint)
    }

    /** 根据是否有 API Key，显示/移除"请先设置 API"提示 */
    private fun updateSetupHint() {
        if (apiKey.isNotEmpty()) {
            // 已配置 API：移除提示
            setupHintView?.let { messageList.removeView(it) }
            setupHintView = null
        } else if (setupHintView == null) {
            // 未配置：展示提示（若未展示过）
            setupHintView = addMessage(getString(R.string.msg_setup_hint), isUser = false)
        }
    }

    /** 动态给聊天区设置底部 padding = 浮动输入面板本体高度 */
    private fun updateChatBottomPadding() {
        inputPanel.post {
            val target = if (inputPanel.height > 0) inputPanel.height + dp(8) else dp(130)
            if (scroll.paddingBottom != target) {
                scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, target)
            }
        }
    }

    /** 结束后把当前 AI 气泡渲染为 Markdown（流式期间只显示明文），表格用系统 TableLayout */
    private fun renderCurrentAiMarkdown() {
        val container = currentAiView ?: return
        val textView = currentAiText ?: return
        val plain = textView.text.toString()
        if (plain.isBlank()) return

        val blocks = Markdown.splitBlocks(plain)
        val hasTable = blocks.any { it.second }
        if (!hasTable) {
            // 无表格：整体渲染进现有 TextView
            textView.text = Markdown.render(plain)
        } else {
            // 有表格：清空容器，按块混排
            container.removeAllViews()
            for ((blockText, isTable) in blocks) {
                if (isTable) {
                    container.addView(buildTableFromMarkdown(blockText))
                } else {
                    val tv =
                        TextView(this).apply {
                            text = Markdown.render(blockText)
                            textSize = 15f
                            setLineSpacing(0f, 1.3f)
                            setTextColor(0xFF303030.toInt())
                        }
                    container.addView(tv)
                }
            }
        }
        scrollToBottom()
    }

    /**
     * 从 Markdown 表格文本构建网格表格（系统自带控件 + 权重平分列宽）。
     * 返回垂直 LinearLayout（每行一个水平 LinearLayout，每列 TextView 用 weight 平分）。
     */
    private fun buildTableFromMarkdown(mdTable: String): android.widget.LinearLayout {
        val lines = mdTable.split("\n").filter { it.trim().isNotBlank() }
        val dataLines = ArrayList<String>()
        var first = true
        for (l in lines) {
            if (first) {
                dataLines.add(l)
                first = false
                continue
            }
            if (isTableSeparatorLine(l.trim())) continue
            dataLines.add(l)
        }
        if (dataLines.isEmpty()) return android.widget.LinearLayout(this)

        // 列数 = 第一行的单元格数
        val colCount = parseMarkdownTableCell(dataLines[0]).size.coerceAtLeast(1)

        val container =
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams =
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            }

        dataLines.forEachIndexed { rowIndex, rowText ->
            val cells = parseMarkdownTableCell(rowText)
            val row =
                android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams =
                        android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                }
            for (ci in 0 until colCount) {
                val cellText = if (ci < cells.size) cells[ci] else ""
                val tv =
                    TextView(this).apply {
                        text = cellText
                        textSize = 14f
                        // 允许换行（不限制 maxLines），并设置 minWidth 让长文本折行
                        setLineSpacing(0f, 1.1f)
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        if (rowIndex == 0) typeface = Typeface.DEFAULT_BOLD
                        setTextColor(0xFF303030.toInt())
                    }
                val lp =
                    android.widget.LinearLayout.LayoutParams(
                        0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                row.addView(tv, lp)
                // 列分隔线（最后一列后不加）
                if (ci < colCount - 1) {
                    row.addView(
                        View(this).apply {
                            setBackgroundColor(0xFFDADADA.toInt())
                        },
                        android.widget.LinearLayout.LayoutParams(
                            dp(1),
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            }
            container.addView(row)
            // 斑马纹
            if (rowIndex % 2 == 1) row.setBackgroundColor(0xFFF3F6FB.toInt())
            // 行分隔线（表头下 + 数据行之间）
            if (rowIndex == 0 || (rowIndex < dataLines.size - 1)) {
                container.addView(
                    View(this).apply {
                        setBackgroundColor(if (rowIndex == 0) 0xFFC0C0C0.toInt() else 0xFFE0E0E0.toInt())
                    },
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1),
                    ),
                )
            }
        }
        return container
    }

    private fun isTableSeparatorLine(line: String): Boolean {
        if (!line.startsWith("|")) return false
        val inner = line.removePrefix("|").removeSuffix("|").trim()
        return inner.isNotEmpty() && inner.split("|").all { it.trim().matches(Regex("^:?-+:?$")) }
    }

    private fun parseMarkdownTableCell(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.dropLast(1)
        return s.split("|").map { it.trim() }
    }

    private fun finishTurn() {
        // 结束：将明文渲染为 Markdown（AI 消息）
        renderCurrentAiMarkdown()
        busy = false
        currentAiView = null
        currentAiText = null
        sendBtn.alpha = 1f
        input.requestFocus()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
