package com.coderGtm.yantra.terminal

import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.core.provider.FontRequest
import androidx.core.provider.FontsContractCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.coderGtm.yantra.BuildConfig
import com.coderGtm.yantra.DEFAULT_TERMINAL_FONT_NAME
import com.coderGtm.yantra.NO_LOG_COMMANDS
import com.coderGtm.yantra.R
import com.coderGtm.yantra.applyLauncherBackground
import com.coderGtm.yantra.activities.MainActivity
import com.coderGtm.yantra.activities.main.MainActivityBehavior
import com.coderGtm.yantra.blueprints.BaseCommand
import com.coderGtm.yantra.blueprints.YantraLauncherDialog
import com.coderGtm.yantra.contactsManager
import com.coderGtm.yantra.getAliases
import com.coderGtm.yantra.getCurrentTheme
import com.coderGtm.yantra.getInit
import com.coderGtm.yantra.getUserName
import com.coderGtm.yantra.getUserNamePrefix
import com.coderGtm.yantra.isPro
import com.coderGtm.yantra.models.Alias
import com.coderGtm.yantra.ankush.Ankush
import com.coderGtm.yantra.commands.alias.updateAliasList
import com.coderGtm.yantra.models.AppBlock
import com.coderGtm.yantra.models.ShortcutBlock
import com.coderGtm.yantra.models.Suggestion
import com.coderGtm.yantra.requestCmdInputFocusAndShowKeyboard
import com.coderGtm.yantra.runInitTasks
import com.coderGtm.yantra.suggestions.CompletionInput
import com.coderGtm.yantra.suggestions.CompletionResult
import com.coderGtm.yantra.suggestions.SuggestionEngine
import com.coderGtm.yantra.suggestions.buildCommandCompletionSpecs
import com.coderGtm.yantra.suggestions.tokenize
import com.coderGtm.yantra.ui.screens.main.MainActivityUiRefs
import com.coderGtm.yantra.vibrate
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.TimerTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Terminal(
    val activity: Activity,
    val binding: MainActivityUiRefs,
    val preferenceObject: SharedPreferences
) {
    private val fontSize = preferenceObject.getInt("fontSize", 16).toFloat()
    private val hideKeyboardOnEnter = preferenceObject.getBoolean("hideKeyboardOnEnter", true)
    private val cacheSize = 5
    private val vibrationPermission = preferenceObject.getBoolean("vibrationPermission",true)
    private val getPrimarySuggestions = preferenceObject.getBoolean("getPrimarySuggestions",true)
    private val getSecondarySuggestions = preferenceObject.getBoolean("getSecondarySuggestions",true)
    
    private var commandQueue: MutableList<String> = mutableListOf()

    // Ankush: password prompt state (next input is consumed as a password).
    private enum class AnkushPrompt {
        HELP, AUTHORIZE, PASSWD_CURRENT, PASSWD_NEW, PASSWD_CONFIRM, DECOY_NEW, DECOY_CONFIRM
    }
    private var ankushPrompt: AnkushPrompt? = null
    private var ankushPromptStealth = false
    private var ankushPendingAction: (() -> Unit)? = null
    private var ankushNewPassword: String? = null
    private var ankushUnlockedUntil = 0L
    private val ankushHandler = Handler(Looper.getMainLooper())
    private val ankushClearScreen = Runnable { binding.terminalOutput.removeAllViews() }
    private var cmdHistoryCursor = -1
    private var commandCache = mutableListOf<Map<String, BaseCommand>>()

    val theme = getCurrentTheme(activity, preferenceObject)
    val commands = getAvailableCommands(activity)
    var primarySuggestions: MutableList<Suggestion> = mutableListOf()
    var initialized = false
    var initTasksQueued = false
    var typeface: Typeface? = Typeface.createFromAsset(activity.assets, "fonts/source_code_pro.ttf")
    var dominantFontColor: Int? = null
    var isSleeping = false
    var sleepTimer: TimerTask? = null
    var contactsFetched: Boolean = false
    var contactNames = HashSet<String>()
    var appListFetched: Boolean = false
    var shortcutListFetched: Boolean = false
    var workingDir = ""
    var cmdHistory = ArrayList<String>()
    var username = binding.username

    lateinit var appList: ArrayList<AppBlock>
    lateinit var shortcutList: ArrayList<ShortcutBlock>
    lateinit var aliasList: MutableList<Alias>

    val suggestionEngine: SuggestionEngine = SuggestionEngine(
        buildCommandCompletionSpecs(
            getThemes = { terminalPreferenceThemeNames(preferenceObject) },
            getTodoArguments = { buildTodoArguments(preferenceObject) },
            getWeatherFields = { com.coderGtm.yantra.commands.weather.VALID_WEATHER_FIELDS },
            getAppCategories = { com.coderGtm.yantra.commands.list.AppCategories.ALL },
        ).filterKeys { it in commands }
    )

    fun buildSuggestionState(): TerminalSuggestionState = TerminalSuggestionState(
        appNames = if (this::appList.isInitialized) appList.map { it.appName } else emptyList(),
        packageNames = if (this::appList.isInitialized) appList.map { it.packageName } else emptyList(),
        shortcutLabels = if (this::shortcutList.isInitialized) shortcutList.map { it.label } else emptyList(),
        contactNames = contactNames.toList(),
        commandNames = commands.keys.toList(),
        aliasKeys = if (this::aliasList.isInitialized) aliasList.map { it.key } else emptyList(),
    )

    private val suggestionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var suggestionJob: Job? = null

    fun scheduleSuggestions(
        rawInput: String,
        primaryEnabled: Boolean,
        secondaryEnabled: Boolean,
    ) {
        suggestionJob?.cancel()
        // Ankush: suggestions are disabled entirely (nothing is shown while typing).
        if (!Ankush.SUGGESTIONS_ENABLED) {
            binding.suggestionsTab.removeAllViews()
            return
        }
        suggestionJob = suggestionScope.launch {
            delay(75)
            // Ankush: never suggest anything while a password is being typed.
            if (ankushPrompt != null) {
                binding.suggestionsTab.removeAllViews()
                return@launch
            }
            // Ankush: suggest renamed commands under their new names, hide original names
            // of renamed commands, and keep (time-locked) aliases out of suggestions.
            val ankushRenames = Ankush.getRenames(preferenceObject) // original -> custom
            val ankushReverse = ankushRenames.entries.associate { it.value to it.key } // custom -> original
            val aliasKeys = aliasList.map { it.key }.toSet()
            val snapshotCommands = commands.keys.filter { it !in ankushRenames }.toSet()
            val snapshotAliases = ankushReverse
            val state = buildSuggestionState()
            val sources = TerminalSuggestionSources(this@Terminal, state)
            val orderedPrimary = primarySuggestions
                .filter { !it.isHidden && it.text !in aliasKeys }
                .map { ankushRenames[it.text] ?: it.text }
            // Preserve the old "contacts not fetched yet" message for the call command.
            val firstToken = rawInput.trim().split(" ").firstOrNull()
            val firstLower = firstToken?.lowercase()
            val blockSecondary = firstLower != null && firstLower in ankushRenames && firstLower !in ankushReverse
            val effectiveFirst = if (firstToken != null) snapshotAliases[firstToken] ?: firstToken.lowercase() else null
            if (effectiveFirst == "call" && !contactsFetched && secondaryEnabled) {
                binding.suggestionsTab.removeAllViews()
                binding.addSuggestion(
                    text = activity.getString(R.string.contacts_not_fetched_yet),
                    color = theme.suggestionTextColor,
                    fontSize = 14.5f,
                    typeface = typeface,
                    style = Typeface.BOLD_ITALIC,
                    onClick = {},
                )
                return@launch
            }
            val results = withContext(Dispatchers.Default) {
                suggestionEngine.complete(
                    input = CompletionInput(rawText = rawInput, cursor = rawInput.length),
                    commands = snapshotCommands,
                    aliases = snapshotAliases,
                    sources = sources,
                    primarySuggestionsEnabled = primaryEnabled,
                    secondarySuggestionsEnabled = secondaryEnabled && !blockSecondary,
                    orderedPrimarySuggestions = orderedPrimary,
                )
            }
            renderSuggestions(results, rawInput)
        }
    }

    private fun renderSuggestions(results: List<CompletionResult>, rawInput: String) {
        binding.suggestionsTab.removeAllViews()
        results.forEach { result ->
            binding.addSuggestion(
                text = result.displayText,
                color = theme.suggestionTextColor,
                fontSize = 14.5f,
                typeface = typeface,
                style = Typeface.BOLD,
                onClick = {
                    val currentText = binding.cmdInput.text.toString()
                    val applied = currentText.substring(0, result.edit.start) +
                        result.edit.replacement +
                        currentText.substring(result.edit.end)
                    binding.cmdInput.setText(applied)
                    binding.cmdInput.setSelection(applied.length)
                    requestCmdInputFocusAndShowKeyboard(binding)
                    if (!result.isPrimary && result.allowAutoExecute && preferenceObject.getBoolean("actOnSuggestionTap", false)) {
                        this@Terminal.handleCommand(applied)
                        binding.cmdInput.setText("")
                    }
                },
                onLongClick = if (result.isPrimary) {
                    {
                        val commandClass = commands[result.commandName]
                        if (commandClass != null) {
                            val metadata = commandClass.getDeclaredConstructor(Terminal::class.java)
                                .newInstance(this@Terminal).metadata
                            YantraLauncherDialog(activity).showInfo(
                                title = metadata.helpTitle,
                                message = metadata.description,
                                positiveButton = activity.getString(R.string.ok),
                            )
                        }
                    }
                } else {
                    null
                },
            )
        }
        // Preserve actOnLastSecondarySuggestion auto-execute behavior.
        if (results.size == 1 && !results[0].isPrimary &&
            preferenceObject.getBoolean("actOnLastSecondarySuggestion", false)
        ) {
            val effectiveCommand = results[0].commandName
            // Don't auto-execute for some commands.
            if (effectiveCommand == "call" || effectiveCommand == "time" || effectiveCommand == "bg" ||
                effectiveCommand == "notepad" || effectiveCommand == "todo" || effectiveCommand == "run"
            ) {
                return
            }
            // Don't auto-execute if only flag suggestion.
            if (results[0].displayText.startsWith("-")) {
                return
            }
            // Don't auto-execute if no input after primary command.
            val tokens = tokenize(CompletionInput(rawText = rawInput, cursor = rawInput.length))
            if (tokens.tokens.size <= 1) {
                return
            }
            output(activity.getString(R.string.auto_executing_suggestion), theme.successTextColor, Typeface.ITALIC)
            val result = results.first()
            val currentText = binding.cmdInput.text.toString()
            val applied = currentText.substring(0, result.edit.start) +
                result.edit.replacement +
                currentText.substring(result.edit.end)
            binding.cmdInput.setText(applied)
            binding.suggestionsTab.removeAllViews()
            handleCommand(applied)
            binding.cmdInput.setText("")
            Thread {
                Thread.sleep(500)
                activity.runOnUiThread {
                    binding.cmdInput.setText("")
                }
            }.start()
        }
    }

    fun initialize() {
        if (preferenceObject.getBoolean("useModernPromptDesign", false)) {
            binding.modernPrompt.visible = true
            binding.modernPrompt.username = getUserName(preferenceObject)
            binding.username.visibility = android.view.View.GONE
        }

        activity.requestedOrientation = preferenceObject.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        goFullScreen()
        enforceThemeComponents()
        applyLauncherBackground(activity, binding, preferenceObject, theme.bgColor)
        setTypeface()
        setArrowKeys(preferenceObject, binding)
        binding.upBtn.setOnClickListener { cmdUp() }
        binding.downBtn.setOnClickListener { cmdDown() }
        setTextChangedListener()
        createTouchListeners()
        applyExtraPrivacy()
        aliasList = getAliases(preferenceObject)
        primarySuggestions = reorderPrimarySuggestions(preferenceObject, getPrimarySuggestionsList(getAvailableCommands(activity), aliasList))
        checkAliasNames()
        setInputListener()
        setLauncherAppsListener(this@Terminal)
        appList = getAppsList(this@Terminal)
        shortcutList = getShortcutList(this@Terminal)
        showSuggestions(binding.cmdInput.text.toString(), getPrimarySuggestions, getSecondarySuggestions, this@Terminal)
        //fetching contacts if permitted
        if (ContextCompat.checkSelfPermission(activity.baseContext, android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            Thread {
                contactsManager(this)
            }.start()
        }
        // Ankush: no Play Store update checks (could pull in the original app).
    }

    private fun enforceThemeComponents() {
        username.textSize = fontSize
        binding.cmdInput.textSize = fontSize
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        setPromptText()
        binding.suggestionsTab.backgroundColorInt = theme.suggestionBgColor
        username.setTextColor(theme.inputLineTextColor)
        binding.cmdInput.setTextColor(theme.inputLineTextColor)
        binding.cmdInput.cursorColorInt = theme.inputLineTextColor
        binding.upBtn.setTextColor(theme.resultTextColor)
        binding.downBtn.setTextColor(theme.resultTextColor)
    }
    private fun setTypeface() {
        val fontName = if (isPro(activity)) {
            preferenceObject.getString("font", DEFAULT_TERMINAL_FONT_NAME) ?: DEFAULT_TERMINAL_FONT_NAME
        }
        else {
            DEFAULT_TERMINAL_FONT_NAME
        }
        if (fontName.endsWith(".ttf")) {
            val fontFile = File(activity.filesDir, fontName)
            if (fontFile.exists()) {
                typeface = Typeface.createFromFile(fontFile)
                if (!preferenceObject.getBoolean("useModernPromptDesign", false)) {
                    username.setTypeface(typeface, Typeface.BOLD)
                }
                binding.cmdInput.typeface = typeface
                finishInitialization()
            }
            return
        }
        val request = FontRequest(
            "com.google.android.gms.fonts",
            "com.google.android.gms",
            fontName,
            R.array.com_google_android_gms_fonts_certs
        )
        val callback = object : FontsContractCompat.FontRequestCallback() {

            override fun onTypefaceRetrieved(rTypeface: Typeface) {
                typeface = rTypeface
                if (!preferenceObject.getBoolean("useModernPromptDesign", false)) {
                    username.setTypeface(typeface, Typeface.BOLD)
                }
                binding.cmdInput.typeface = typeface
                finishInitialization()
            }

            override fun onTypefaceRequestFailed(reason: Int) {
                typeface = Typeface.createFromAsset(activity.assets, "fonts/source_code_pro.ttf")
                if (!preferenceObject.getBoolean("useModernPromptDesign", false)) {
                    username.setTypeface(typeface, Typeface.BOLD)
                }
                binding.cmdInput.typeface = typeface
                finishInitialization()
            }
        }
        //make handler to fetch font in background
        val handler = Handler(Looper.getMainLooper())
        FontsContractCompat.requestFont(activity, request, callback, handler)
    }

    private fun setInputListener() {
        binding.cmdInput.setOnEditorActionListener { v, actionId, event ->
            return@setOnEditorActionListener when (actionId) {
                EditorInfo.IME_ACTION_SEND -> {
                    val inputReceived = binding.cmdInput.text.toString().trim()
                    handleInput(inputReceived)
                    true
                }
                else -> true
            }
        }
    }
    private fun setTextChangedListener() {
        if (getPrimarySuggestions || getSecondarySuggestions) {
            registerTextChangedListener()
        }
    }
    private fun registerTextChangedListener() {
        binding.cmdInput.addTextChangedListener {
            showSuggestions(it.toString(), getPrimarySuggestions, getSecondarySuggestions, this@Terminal)
        }
    }
    fun handleInput(input: String) {
        suggestionJob?.cancel()
        binding.suggestionsTab.removeAllViews()
        handleCommand(input)
        binding.cmdInput.setText("")
        if (hideKeyboardOnEnter) hideSoftKeyboard()
        goFullScreen()
    }
    private fun hideSoftKeyboard() {
        binding.hideKeyboard()
    }
    private fun goFullScreen() {
        if (preferenceObject.getBoolean("fullScreen",false)) {
            val windowInsetsController = ViewCompat.getWindowInsetsController(activity.window.decorView)
            // Hide the system bars.
            windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    private fun setArrowKeys(preferenceObject: SharedPreferences, binding: MainActivityUiRefs) {
        // Ankush: no history, so no history arrows.
        val showArrowKeys = Ankush.HISTORY_ENABLED && preferenceObject.getBoolean("showArrowKeys",true)
        if (showArrowKeys) {
            val arrowSize = preferenceObject.getInt("arrowSize", 65).toFloat()
            binding.upBtn.textSize = arrowSize
            binding.downBtn.textSize = arrowSize
            binding.upBtn.visibility = View.VISIBLE
            binding.downBtn.visibility = View.VISIBLE
        }
        else {
            binding.upBtn.visibility = View.GONE
            binding.downBtn.visibility = View.GONE
        }
    }
    fun executeCommandsInQueue() {
        while (commandQueue.isNotEmpty() && !isSleeping) {
            val cmdToExecute = commandQueue.removeAt(0)
            handleCommand(cmdToExecute)
        }
    }
    private fun createTouchListeners() {
        binding.scrollView.setGestureListenerCallback((activity as MainActivity))
        // for keyboard open
        binding.inputLineLayout.setOnClickListener {
            requestCmdInputFocusAndShowKeyboard(binding)
        }
    }

    private fun checkAliasNames() {
        val commandNames = commands.keys
        for (i in aliasList.indices) {
            if (commandNames.contains(aliasList[i].key)) {
                output("--> Alias name cannot be an existing command name. Hence, alias '${aliasList[i].key}' needs to be unaliased to use the '${aliasList[i].key}' command.", theme.warningTextColor, null)
            }
        }
    }
    private fun incrementNumOfCommandsEntered(
        preferenceObject: SharedPreferences,
        preferenceEditObject: SharedPreferences.Editor
    ) {
        val n = preferenceObject.getLong("numOfCmdsEntered",0)
        preferenceEditObject.putLong("numOfCmdsEntered",n+1).apply()
    }
    fun output(text: String, color: Int, style: Int?, markdown: Boolean = false): String {
        val renderColor = dominantFontColor ?: color
        var outputId: String? = null
        val addOutput = {
            outputId = binding.addTextOutput(
                text = text,
                color = renderColor,
                style = style,
                markdown = markdown,
                typeface = typeface,
                fontSize = fontSize,
            )
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            addOutput()
        } else {
            val latch = CountDownLatch(1)
            activity.runOnUiThread {
                addOutput()
                latch.countDown()
            }
            latch.await()
        }
        // if error then vibrate
        if (renderColor == theme.errorTextColor && vibrationPermission) {
            vibrate(activity = activity)
        }
        return outputId!!
    }

    fun updateOutputItem(id: String, text: String, color: Int, style: Int?, markdown: Boolean = false) {
        val renderColor = dominantFontColor ?: color
        activity.runOnUiThread {
            binding.updateTextOutput(
                id = id,
                text = text,
                color = renderColor,
                style = style,
                markdown = markdown,
                typeface = typeface,
                fontSize = fontSize,
            )
        }
        if (renderColor == theme.errorTextColor && vibrationPermission) {
            vibrate(activity = activity)
        }
    }
    fun setPromptText() {
        val isModern = preferenceObject.getBoolean("useModernPromptDesign", false)
        if (preferenceObject.getBoolean("showCurrentFolderInPrompt", false) && workingDir.isNotEmpty()) {
            val currentFolder = workingDir.split("/").last()
            if (isModern) {
                binding.modernPrompt.username = "${getUserName(preferenceObject)}/../$currentFolder"
                return
            }
            username.text = "${getUserNamePrefix(preferenceObject)}${getUserName(preferenceObject)}/../$currentFolder>"
            return
        }
        if (isModern) {
            binding.modernPrompt.username = getUserName(preferenceObject)
            return
        }
        username.text = "${getUserNamePrefix(preferenceObject)}${getUserName(preferenceObject)}>"
    }
    private fun getCommandInstance(commandName: String): BaseCommand? {
        val cachedCommand = commandCache.find { it.containsKey(commandName) }

        if (cachedCommand != null) {
            commandCache.remove(cachedCommand)
            commandCache.add(0, cachedCommand)
            return cachedCommand[commandName]
        }
        else {
            if (commandCache.size >= cacheSize) {
                commandCache.removeAt(commandCache.size - 1)
            }

            val commandClass = commands[commandName]
            if (commandClass != null) {
                val newCommand = mapOf(
                    commandName to commandClass.getDeclaredConstructor(Terminal::class.java)
                        .newInstance(this)
                )
                commandCache.add(0, newCommand)
                return newCommand[commandName]
            }
            return null
        }
    }
    private fun finishInitialization() {
        // Update reactive font state so the Compose input prompt recomposes with the real font
        binding.modernPrompt.fontFamily = typeface?.let { androidx.compose.ui.text.font.FontFamily(it) }
        printIntro()
        // finishInitialization is the moment the terminal becomes initialized,
        // so pass true explicitly (initialized is set to true just below).
        if (MainActivityBehavior.shouldRunInit(true, isPro(activity), initTasksQueued)) {
            initTasksQueued = true
            Thread {
                val initList = getInit(preferenceObject)
                runInitTasks(initList, preferenceObject, this@Terminal)
            }.start()
        }
        initialized = true
    }

    private fun printIntro() {
        output("${activity.applicationInfo.loadLabel(activity.packageManager)} (v${BuildConfig.VERSION_NAME}) on ${Build.MANUFACTURER} ${Build.MODEL}",theme.resultTextColor, Typeface.BOLD)
        output("==================",theme.resultTextColor, Typeface.BOLD)
    }

    fun cmdDown() {
        binding.cmdInput.requestFocus()
        if (cmdHistoryCursor<(cmdHistory.size-1)) {
            cmdHistoryCursor++
            binding.cmdInput.setText(cmdHistory[cmdHistoryCursor])
            binding.cmdInput.setSelection(binding.cmdInput.text!!.length)
            requestCmdInputFocusAndShowKeyboard(binding)
        }
    }
    fun cmdUp() {
        binding.cmdInput.requestFocus()
        if (cmdHistoryCursor>0) {
            cmdHistoryCursor--
            binding.cmdInput.setText(cmdHistory[cmdHistoryCursor])
            binding.cmdInput.setSelection(binding.cmdInput.text!!.length)
            requestCmdInputFocusAndShowKeyboard(binding)
        }
    }
    fun handleCommand(command: String, isAlias: Boolean = false, logCmd: Boolean = true) {
        if (isSleeping) {
            commandQueue.add(command)
            return
        }
        // Ankush: a pending password prompt consumes this input (never echoed or stored).
        if (!isAlias && ankushPrompt != null) {
            handleAnkushPromptInput(command)
            return
        }
        val commandName = command.trim().split(" ").firstOrNull()
        // Ankush: hidden management commands (never echoed, never kept in history).
        if (!isAlias && commandName != null && commandName.lowercase() in Ankush.MANAGEMENT_COMMANDS) {
            handleAnkushCommand(command.trim())
            return
        }
        if (!isAlias) {
            if (Ankush.ECHO_COMMANDS && logCmd && !NO_LOG_COMMANDS.contains(commandName?.lowercase())) {
                echoCommand(command)
            }
            if (command.trim()!="") {
                if (Ankush.HISTORY_ENABLED) {
                    cmdHistory.add(command)
                    cmdHistoryCursor = cmdHistory.size
                }
                incrementNumOfCommandsEntered(preferenceObject, preferenceObject.edit())
            }
        }
        commandName?.let { _ ->
            aliasList.find { it.key == commandName }?.let { alias ->
                // Ankush: aliases are time-locked. Type "<alias> <code>" (see Ankush.timeCode),
                // e.g. at 1:02 "fk 7". Without the right code it acts like an unknown command.
                val parts = command.trim().split(" ").filter { it.isNotEmpty() }
                val rest: List<String>
                if (isAlias) {
                    rest = parts.drop(1) // alias used inside another alias: already unlocked
                } else if (!Ankush.isAliasLock(preferenceObject)) {
                    // alias lock OFF: no code needed (a correct code is still accepted and dropped)
                    val code = parts.getOrNull(1)?.toIntOrNull()
                    rest = if (code != null && code in Ankush.acceptedTimeCodes(preferenceObject)) parts.drop(2) else parts.drop(1)
                } else {
                    val code = parts.getOrNull(1)?.toIntOrNull()
                    if (code == null || code !in Ankush.acceptedTimeCodes(preferenceObject)) {
                        showNotRecognized(commandName.toString())
                        return@handleCommand
                    }
                    rest = parts.drop(2)
                }
                val newCommand = (listOf(alias.value.trim()) + rest).joinToString(" ")
                handleCommand(newCommand, true)
                return@handleCommand
            }
        }
        // Ankush: resolve renamed commands. The custom name runs the original command,
        // and the original name stops working (except inside aliases).
        var effectiveCommand = command.trim()
        var effectiveName = commandName.toString().lowercase()
        val aliasLock = Ankush.isAliasLock(preferenceObject)
        val renames = Ankush.getRenames(preferenceObject)
        if (renames.isNotEmpty() && commandName != null) {
            val original = renames.entries.firstOrNull { it.value == effectiveName }?.key
            if (original != null) {
                effectiveCommand = original + effectiveCommand.substring(commandName.length)
                effectiveName = original
            } else if (effectiveName in renames && !isAlias && aliasLock) {
                showNotRecognized(commandName)
                return
            }
        }
        // Ankush: help (and similar) only exists while the alias lock is off.
        if (aliasLock && effectiveName in Ankush.HIDDEN_WHILE_LOCKED) {
            showNotRecognized(commandName.toString())
            return
        }
        val commandInstance = getCommandInstance(effectiveName)
        if (commandInstance != null) {
            // Ankush: commands that reveal activity or change setup need the password.
            if (effectiveName in Ankush.PROTECTED_COMMANDS) {
                val finalCommand = effectiveCommand
                requireAuth(stealth = false, triggerName = commandName.toString()) {
                    commandInstance.execute(finalCommand)
                }
                return
            }
            commandInstance.execute(effectiveCommand)
        }
        else {
            if (command.trim() == "") return
            showNotRecognized(commandName.toString())
        }
    }

    private fun echoCommand(command: String) {
        if (preferenceObject.getBoolean("useModernPromptDesign", false)) {
            addChatBubble(getUserName(preferenceObject), command)
        } else {
            output(getUserNamePrefix(preferenceObject)+getUserName(preferenceObject)+"> $command", theme.commandColor, null)
        }
    }

    private fun showNotRecognized(commandName: String) {
        // Ankush: no "Did you mean ...?" hint, so command names are never revealed.
        output("$commandName is not a recognized command.", theme.errorTextColor, null)
    }

    // ---------------- Ankush personal commands ----------------

    /** Called when the launcher goes to the background (app opened, home left, screen off). */
    fun ankushOnStop() {
        endAnkushPrompt()
        ankushUnlockedUntil = 0L
        activity.runOnUiThread {
            ankushHandler.removeCallbacks(ankushClearScreen)
            binding.terminalOutput.removeAllViews()
            binding.cmdInput.setText("")
        }
    }

    /** Double tap on the home screen: wipe the screen, re-lock, and turn the display off. */
    fun ankushDoubleTapLock() {
        endAnkushPrompt()
        ankushUnlockedUntil = 0L
        Ankush.clearUnlocked(preferenceObject)
        activity.runOnUiThread {
            ankushHandler.removeCallbacks(ankushClearScreen)
            binding.terminalOutput.removeAllViews()
            binding.cmdInput.setText("")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                com.coderGtm.yantra.commands.lock.lockDeviceByAccessibilityService(activity)
            } else {
                com.coderGtm.yantra.commands.lock.lockDeviceByAdmin(activity)
            }
        }
    }

    private fun applyExtraPrivacy() {
        val enabled = Ankush.isExtraPrivacy(preferenceObject)
        activity.runOnUiThread {
            if (enabled) {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    private fun isAnkushUnlocked(): Boolean = System.currentTimeMillis() < ankushUnlockedUntil

    private fun notRecognizedMessage(name: String) {
        output("$name is not a recognized command.", theme.errorTextColor, null)
    }

    /**
     * Runs [action] right away if unlocked, otherwise asks for the password first.
     * stealth = true: looks exactly like an unknown command, then silently waits for the password.
     */
    private fun requireAuth(stealth: Boolean, triggerName: String, alwaysAsk: Boolean = false, action: () -> Unit) {
        if (!alwaysAsk && isAnkushUnlocked()) {
            action()
            return
        }
        ankushPendingAction = action
        if (stealth) notRecognizedMessage(triggerName) else output("Enter password:", theme.warningTextColor, null)
        beginAnkushPrompt(AnkushPrompt.AUTHORIZE, stealth)
    }

    private fun handleAnkushCommand(command: String) {
        val args = command.split(" ").filter { it.isNotEmpty() }
        val name = args[0]
        when (name.lowercase()) {
            Ankush.HELP_COMMAND -> {
                if (isAnkushUnlocked()) {
                    showAnkushHelp(decoy = false)
                } else {
                    notRecognizedMessage(name)
                    beginAnkushPrompt(AnkushPrompt.HELP, stealth = true)
                }
            }
            Ankush.PASSWD_COMMAND -> {
                // always asks for the current password, even when unlocked
                notRecognizedMessage(name)
                beginAnkushPrompt(AnkushPrompt.PASSWD_CURRENT, stealth = true)
            }
            Ankush.RENAME_COMMAND -> requireAuth(true, name) { ankushRename(args) }
            Ankush.RESET_COMMAND -> requireAuth(true, name) { ankushReset(args) }
            Ankush.DECOY_COMMAND -> requireAuth(true, name) { ankushDecoy(args) }
            Ankush.PRIVACY_COMMAND -> requireAuth(true, name) { ankushPrivacy(args) }
            // switching the alias lock always asks for the password, even inside the unlock window
            Ankush.ALIAS_LOCK_COMMAND -> requireAuth(true, name, alwaysAsk = true) { ankushAliasLock(args) }
            Ankush.OFFSET_COMMAND -> requireAuth(true, name) { ankushOffset(args) }
            Ankush.GUARD_COMMAND -> requireAuth(true, name, alwaysAsk = true) { ankushGuard(args) }
        }
    }

    private fun beginAnkushPrompt(prompt: AnkushPrompt, stealth: Boolean) {
        ankushPrompt = prompt
        ankushPromptStealth = stealth
        activity.runOnUiThread { binding.cmdInput.setPasswordMode(true) }
    }

    private fun endAnkushPrompt() {
        ankushPrompt = null
        ankushPromptStealth = false
        ankushPendingAction = null
        ankushNewPassword = null
        activity.runOnUiThread { binding.cmdInput.setPasswordMode(false) }
    }

    /** Message for a failed password. Stealth prompts keep pretending nothing is there. */
    private fun ankushPasswordFailed(result: Ankush.PasswordResult, stealth: Boolean) {
        when {
            stealth -> output("Command not recognized.", theme.errorTextColor, null)
            result == Ankush.PasswordResult.LOCKED ->
                output("Too many wrong attempts. Try again later.", theme.errorTextColor, null)
            else -> output("Wrong password.", theme.errorTextColor, null)
        }
    }

    private fun unlockAnkush() {
        ankushUnlockedUntil = System.currentTimeMillis() + Ankush.UNLOCK_WINDOW_MS
        Ankush.markUnlocked(preferenceObject) // let Ankush Guard allow Settings briefly
    }

    private fun handleAnkushPromptInput(input: String) {
        val value = input.trim()
        val stealth = ankushPromptStealth
        when (ankushPrompt) {
            AnkushPrompt.HELP -> {
                endAnkushPrompt()
                when (val result = Ankush.verifyPassword(preferenceObject, value)) {
                    Ankush.PasswordResult.CORRECT -> { unlockAnkush(); showAnkushHelp(decoy = false) }
                    Ankush.PasswordResult.DECOY -> showAnkushHelp(decoy = true)
                    else -> ankushPasswordFailed(result, stealth)
                }
            }
            AnkushPrompt.AUTHORIZE -> {
                val action = ankushPendingAction
                endAnkushPrompt()
                val result = Ankush.verifyPassword(preferenceObject, value)
                if (result == Ankush.PasswordResult.CORRECT) {
                    unlockAnkush()
                    action?.invoke()
                } else {
                    ankushPasswordFailed(result, stealth)
                }
            }
            AnkushPrompt.PASSWD_CURRENT -> {
                val result = Ankush.verifyPassword(preferenceObject, value)
                if (result == Ankush.PasswordResult.CORRECT) {
                    ankushPrompt = AnkushPrompt.PASSWD_NEW
                    output("Enter new password:", theme.warningTextColor, null)
                } else {
                    endAnkushPrompt()
                    ankushPasswordFailed(result, stealth)
                }
            }
            AnkushPrompt.PASSWD_NEW -> {
                if (value.length < 4) {
                    endAnkushPrompt()
                    output("Password must be at least 4 characters. Nothing changed.", theme.errorTextColor, null)
                } else {
                    ankushNewPassword = value
                    ankushPrompt = AnkushPrompt.PASSWD_CONFIRM
                    output("Type the new password again:", theme.warningTextColor, null)
                }
            }
            AnkushPrompt.PASSWD_CONFIRM -> {
                val newPassword = ankushNewPassword
                endAnkushPrompt()
                if (newPassword != null && newPassword == value) {
                    Ankush.setPassword(preferenceObject, newPassword)
                    output("Password changed.", theme.successTextColor, null)
                } else {
                    output("Passwords did not match. Nothing changed.", theme.errorTextColor, null)
                }
            }
            AnkushPrompt.DECOY_NEW -> {
                when {
                    value.length < 4 -> {
                        endAnkushPrompt()
                        output("Decoy password must be at least 4 characters. Nothing changed.", theme.errorTextColor, null)
                    }
                    Ankush.isMainPassword(preferenceObject, value) -> {
                        endAnkushPrompt()
                        output("Decoy password must be different from your real password. Nothing changed.", theme.errorTextColor, null)
                    }
                    else -> {
                        ankushNewPassword = value
                        ankushPrompt = AnkushPrompt.DECOY_CONFIRM
                        output("Type the decoy password again:", theme.warningTextColor, null)
                    }
                }
            }
            AnkushPrompt.DECOY_CONFIRM -> {
                val decoy = ankushNewPassword
                endAnkushPrompt()
                if (decoy != null && decoy == value) {
                    Ankush.setDecoyPassword(preferenceObject, decoy)
                    output("Decoy password set. Entering it at ankush-help shows a fake, empty list.", theme.successTextColor, null)
                } else {
                    output("Passwords did not match. Nothing changed.", theme.errorTextColor, null)
                }
            }
            null -> {}
        }
    }

    /** decoy = true shows a harmless fake list: nothing renamed, no aliases. */
    private fun showAnkushHelp(decoy: Boolean) {
        val head = theme.warningTextColor
        val body = theme.resultTextColor
        val good = theme.successTextColor
        val line = "-------------------------"
        fun h(text: String) = output(text, head, Typeface.BOLD)
        fun t(text: String) = output(text, body, null)

        if (decoy) {
            h("Command map")
            t(line)
            t("No commands renamed.")
            t("No aliases.")
            scheduleAnkushHelpClear()
            return
        }

        val prefs = preferenceObject
        val renames = Ankush.getRenames(prefs)
        val aliases = aliasList.sortedBy { it.key }
        val aliasLock = Ankush.isAliasLock(prefs)
        val offset = Ankush.getOffset(prefs)
        val example = (2 + 3) / 2 + offset // 1:02 -> ((1+1)+(2+1))/2 = 2, + offset
        fun onOff(b: Boolean) = if (b) "ON" else "OFF"

        output("ANKUSH - your private setup", good, Typeface.BOLD_ITALIC)
        t(line)

        h("STATUS (things you can toggle)")
        t("Alias lock ........ ${onOff(aliasLock)}   -> ${Ankush.ALIAS_LOCK_COMMAND} on|off")
        t("Time-code offset .. $offset    -> ${Ankush.OFFSET_COMMAND} <0-${Ankush.MAX_OFFSET}>")
        t("Extra privacy ..... ${onOff(Ankush.isExtraPrivacy(prefs))}  -> ${Ankush.PRIVACY_COMMAND} on|off")
        t("Settings guard .... ${onOff(Ankush.isGuard(prefs))}  -> ${Ankush.GUARD_COMMAND} on|off")
        t("Decoy password .... ${if (Ankush.hasDecoyPassword(prefs)) "set" else "not set"}  -> ${Ankush.DECOY_COMMAND} / ${Ankush.DECOY_COMMAND} off")
        t(line)

        h("RENAMED COMMANDS")
        if (renames.isEmpty()) {
            t("None yet. Example: ${Ankush.RENAME_COMMAND} launch luck")
        } else {
            renames.toSortedMap().forEach { (original, custom) ->
                output("$original  ->  $custom", good, null)
            }
            val (o, c) = renames.toSortedMap().entries.first().let { it.key to it.value }
            t("Use the new name, e.g. '$c ...' instead of '$o ...'.")
            t(if (aliasLock) "Old names are disabled while the alias lock is ON." else "Alias lock is OFF: old names work too.")
        }
        t("Aliases that use a renamed command are updated automatically.")
        t(line)

        h("ALIASES")
        if (aliases.isEmpty()) {
            t("None yet. Create one: alias fk=luck whatsapp")
        } else {
            aliases.forEach { output("${it.key}  =  ${it.value}", good, null) }
        }
        if (aliasLock) {
            t("Type: <alias> <time code>, e.g. ${aliases.firstOrNull()?.key ?: "fk"} $example at 1:02")
        } else {
            t("Alias lock is OFF: just type the alias, e.g. ${aliases.firstOrNull()?.key ?: "fk"}")
        }
        t("Create: alias fk=luck whatsapp   Remove: unalias fk   (both ask the password)")
        t("Typing 'alias' alone no longer lists aliases; they are only shown here.")
        t(line)

        h("TIME CODE (12-hour clock)")
        t("((hour + 1) + (minute + 1)) / 2, drop any .5, then + $offset")
        t("Example 1:02 -> (2 + 3) / 2 = 2.5 -> 2 -> 2 + $offset = $example")
        t("The previous minute's code also works. AM and PM give the same code.")
        t(line)

        h("YOUR HIDDEN COMMANDS")
        t("Each one first says 'not recognized'. Then type your password.")
        t("${Ankush.HELP_COMMAND} .............. this screen")
        t("${Ankush.RENAME_COMMAND} <cmd> <new> . e.g. ${Ankush.RENAME_COMMAND} launch luck")
        t("${Ankush.RESET_COMMAND} <name|all> .. e.g. ${Ankush.RESET_COMMAND} luck")
        t("${Ankush.ALIAS_LOCK_COMMAND} on|off ...... strict / relaxed (always asks password)")
        t("${Ankush.OFFSET_COMMAND} <n> .......... e.g. ${Ankush.OFFSET_COMMAND} 9")
        t("${Ankush.PASSWD_COMMAND} ............ change your password")
        t("${Ankush.DECOY_COMMAND} [off] ....... set / remove decoy password")
        t("${Ankush.PRIVACY_COMMAND} on|off .... block screenshots + recents preview")
        t("${Ankush.GUARD_COMMAND} on|off ...... bounce out of Settings when locked")
        t("Decoy password at ${Ankush.HELP_COMMAND} shows a fake list: no renames, no aliases.")
        t("Check a setting without changing it: ${Ankush.ALIAS_LOCK_COMMAND}, ${Ankush.OFFSET_COMMAND}, ${Ankush.PRIVACY_COMMAND} (no on/off).")
        t(line)

        h("SEE YANTRA'S NORMAL COMMANDS")
        t("1) ${Ankush.ALIAS_LOCK_COMMAND} off   2) help  (or: help launch)   3) ${Ankush.ALIAS_LOCK_COMMAND} on")
        t(line)
        t("(This screen clears itself in ${Ankush.HELP_AUTO_CLEAR_MS / 1000} seconds.)")
        scheduleAnkushHelpClear()
    }

    private fun scheduleAnkushHelpClear() {
        activity.runOnUiThread {
            ankushHandler.removeCallbacks(ankushClearScreen)
            ankushHandler.postDelayed(ankushClearScreen, Ankush.HELP_AUTO_CLEAR_MS)
        }
    }

    private fun ankushDecoy(args: List<String>) {
        if (args.getOrNull(1)?.lowercase() == "off") {
            Ankush.setDecoyPassword(preferenceObject, null)
            output("Decoy password removed.", theme.successTextColor, null)
            return
        }
        output("Enter decoy password:", theme.warningTextColor, null)
        beginAnkushPrompt(AnkushPrompt.DECOY_NEW, stealth = false)
    }

    private fun ankushPrivacy(args: List<String>) {
        when (args.getOrNull(1)?.lowercase()) {
            "on" -> Ankush.setExtraPrivacy(preferenceObject, true)
            "off" -> Ankush.setExtraPrivacy(preferenceObject, false)
            null -> {}
            else -> {
                output("Usage: ${Ankush.PRIVACY_COMMAND} on|off", theme.errorTextColor, null)
                return
            }
        }
        applyExtraPrivacy()
        val on = Ankush.isExtraPrivacy(preferenceObject)
        output("Extra privacy is " + (if (on) "ON: screenshots and the recent-apps preview are blocked." else "OFF."), theme.successTextColor, null)
    }

    private fun ankushGuard(args: List<String>) {
        when (args.getOrNull(1)?.lowercase()) {
            "on" -> {
                if (!com.coderGtm.yantra.commands.lock.isAccessibilityServiceEnabled(activity)) {
                    output("Turn on Ankush under Settings > Accessibility first, then run this again.", theme.errorTextColor, null)
                    activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return
                }
                Ankush.setGuard(preferenceObject, true)
            }
            "off" -> Ankush.setGuard(preferenceObject, false)
            null -> {}
            else -> {
                output("Usage: ${Ankush.GUARD_COMMAND} on|off", theme.errorTextColor, null)
                return
            }
        }
        if (Ankush.isGuard(preferenceObject)) {
            output("Ankush Guard is ON: opening Settings bounces back here unless you unlocked in the last ${Ankush.UNLOCK_WINDOW_MS / 1000}s.", theme.successTextColor, null)
            output("To open Settings yourself: run any ankush- command with your password first, then open Settings within ${Ankush.UNLOCK_WINDOW_MS / 1000}s.", theme.resultTextColor, null)
        } else {
            output("Ankush Guard is OFF.", theme.warningTextColor, null)
        }
    }

    private fun ankushOffset(args: List<String>) {
        val arg = args.getOrNull(1)
        if (arg == null) {
            output("Time-code offset is ${Ankush.getOffset(preferenceObject)}.", theme.successTextColor, null)
            return
        }
        val value = arg.toIntOrNull()
        if (value == null || value < 0 || value > Ankush.MAX_OFFSET) {
            output("Usage: ${Ankush.OFFSET_COMMAND} <0-${Ankush.MAX_OFFSET}>   e.g. ${Ankush.OFFSET_COMMAND} 9", theme.errorTextColor, null)
            return
        }
        Ankush.setOffset(preferenceObject, value)
        output("Time-code offset set to $value. Alias codes now end with + $value.", theme.successTextColor, null)
    }

    private fun ankushAliasLock(args: List<String>) {
        when (args.getOrNull(1)?.lowercase()) {
            "on" -> Ankush.setAliasLock(preferenceObject, true)
            "off" -> Ankush.setAliasLock(preferenceObject, false)
            null -> {}
            else -> {
                output("Usage: ${Ankush.ALIAS_LOCK_COMMAND} on|off", theme.errorTextColor, null)
                return
            }
        }
        if (Ankush.isAliasLock(preferenceObject)) {
            output("Alias lock is ON: aliases need the time code, original command names and help are disabled.", theme.successTextColor, null)
        } else {
            output("Alias lock is OFF: aliases work without a code, original command names and help work too.", theme.warningTextColor, null)
        }
    }

    private fun ankushRename(args: List<String>) {
        if (args.size != 3) {
            output("Usage: ${Ankush.RENAME_COMMAND} <command> <new-name>   e.g. ${Ankush.RENAME_COMMAND} launch luck", theme.errorTextColor, null)
            return
        }
        val renames = Ankush.getRenames(preferenceObject)
        val reverse = renames.entries.associate { it.value to it.key }
        val target = args[1].lowercase()
        val newName = args[2].lowercase()
        val original = when {
            target in reverse -> reverse.getValue(target)
            target in commands.keys -> target
            else -> null
        }
        if (original == null) {
            output("'$target' is not a command.", theme.errorTextColor, null)
            return
        }
        val oldName = renames[original] ?: original
        when {
            newName == oldName -> output("Already called '$newName'.", theme.warningTextColor, null)
            newName == original -> ankushApplyRename(original, null, oldName)
            !Ankush.isValidName(newName) ->
                output("New name must start with a letter and use only letters and digits.", theme.errorTextColor, null)
            newName in commands.keys ->
                output("'$newName' is a built-in command name. Pick something else.", theme.errorTextColor, null)
            newName in reverse ->
                output("'$newName' is already used by another command.", theme.errorTextColor, null)
            aliasList.any { it.key.lowercase() == newName } ->
                output("'$newName' is already an alias name.", theme.errorTextColor, null)
            else -> ankushApplyRename(original, newName, oldName)
        }
    }

    private fun ankushReset(args: List<String>) {
        if (args.size != 2) {
            output("Usage: ${Ankush.RESET_COMMAND} <command|all>", theme.errorTextColor, null)
            return
        }
        val renames = Ankush.getRenames(preferenceObject)
        val target = args[1].lowercase()
        if (target == "all") {
            renames.forEach { (original, custom) -> ankushApplyRename(original, null, custom, quiet = true) }
            output("All commands restored to their original names.", theme.successTextColor, null)
            return
        }
        val original = renames.entries.firstOrNull { it.value == target }?.key ?: target.takeIf { it in renames }
        if (original == null) {
            output("'$target' has not been renamed.", theme.warningTextColor, null)
            return
        }
        ankushApplyRename(original, null, renames.getValue(original))
    }

    /** newName == null restores the original name. Aliases are updated to keep working. */
    private fun ankushApplyRename(original: String, newName: String?, oldName: String, quiet: Boolean = false) {
        val renames = Ankush.getRenames(preferenceObject)
        if (newName == null) renames.remove(original) else renames[original] = newName
        Ankush.saveRenames(preferenceObject, renames)
        val currentName = newName ?: original
        var aliasesChanged = false
        for (i in aliasList.indices) {
            val value = aliasList[i].value.trim()
            val first = value.split(" ").firstOrNull() ?: continue
            if (first.lowercase() == oldName || first.lowercase() == original) {
                aliasList[i] = Alias(aliasList[i].key, currentName + value.substring(first.length))
                aliasesChanged = true
            }
        }
        if (aliasesChanged) updateAliasList(aliasList, preferenceObject.edit())
        if (quiet) return
        if (newName == null) {
            output("'$original' restored to its original name.", theme.successTextColor, null)
        } else {
            output("Done. Use '$newName' from now on; '$oldName' no longer works.", theme.successTextColor, null)
        }
    }

    private fun addChatBubble(username: String, command: String) {
        activity.runOnUiThread {
            binding.addChatBubbleOutput(
                username = username,
                command = command,
                commandColor = theme.commandColor,
                fontSize = fontSize,
                typeface = typeface,
            )
        }
    }

    fun cancelSuggestionScope() {
        suggestionScope.cancel()
    }

}
