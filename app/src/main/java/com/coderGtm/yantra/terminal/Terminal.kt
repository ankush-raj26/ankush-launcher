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
import com.coderGtm.yantra.promoteProVersion
import com.coderGtm.yantra.requestCmdInputFocusAndShowKeyboard
import com.coderGtm.yantra.requestUpdateIfAvailable
import com.coderGtm.yantra.runInitTasks
import com.coderGtm.yantra.showRatingAndCommunityPopups
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
    private enum class AnkushPrompt { HELP_PASSWORD, PASSWD_CURRENT, PASSWD_NEW, PASSWD_CONFIRM }
    private var ankushPrompt: AnkushPrompt? = null
    private var ankushNewPassword: String? = null
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
        Thread {
            requestUpdateIfAvailable(preferenceObject, activity)
        }.start()
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
        val showArrowKeys = preferenceObject.getBoolean("showArrowKeys",true)
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
        output(activity.getString(R.string.intro_help_or_community), theme.resultTextColor, Typeface.BOLD)
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
        // Ankush: hidden management commands (not in help/suggestions, not kept in history).
        if (!isAlias && commandName != null && commandName.lowercase() in Ankush.MANAGEMENT_COMMANDS) {
            if (logCmd) echoCommand(command)
            handleAnkushCommand(command.trim())
            return
        }
        if (!isAlias) {
            if (logCmd && !NO_LOG_COMMANDS.contains(commandName?.lowercase())) {
                echoCommand(command)
            }
            if (command.trim()!="") {
                cmdHistory.add(command)
                cmdHistoryCursor = cmdHistory.size
                incrementNumOfCommandsEntered(preferenceObject, preferenceObject.edit())
                showRatingAndCommunityPopups(preferenceObject, preferenceObject.edit(), activity)
                promoteProVersion(this@Terminal, preferenceObject)
            }
        }
        commandName?.let { _ ->
            aliasList.find { it.key == commandName }?.let { alias ->
                // Ankush: aliases are time-locked. Type "<alias> <hour+minute>" (24h clock),
                // e.g. at 01:02 "fk 3". Without the right code it acts like an unknown command.
                val parts = command.trim().split(" ").filter { it.isNotEmpty() }
                val rest: List<String>
                if (isAlias) {
                    rest = parts.drop(1) // alias used inside another alias: already unlocked
                } else {
                    val code = parts.getOrNull(1)?.toIntOrNull()
                    if (code == null || code !in Ankush.acceptedTimeCodes()) {
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
        val renames = Ankush.getRenames(preferenceObject)
        if (renames.isNotEmpty() && commandName != null) {
            val original = renames.entries.firstOrNull { it.value == effectiveName }?.key
            if (original != null) {
                effectiveCommand = original + effectiveCommand.substring(commandName.length)
                effectiveName = original
            } else if (effectiveName in renames && !isAlias) {
                showNotRecognized(commandName)
                return
            }
        }
        val commandInstance = getCommandInstance(effectiveName)
        if (commandInstance != null) {
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

    private fun handleAnkushCommand(command: String) {
        val args = command.split(" ").filter { it.isNotEmpty() }
        when (args[0].lowercase()) {
            Ankush.HELP_COMMAND -> startAnkushPrompt(AnkushPrompt.HELP_PASSWORD, "Enter password:")
            Ankush.PASSWD_COMMAND -> startAnkushPrompt(AnkushPrompt.PASSWD_CURRENT, "Enter current password:")
            Ankush.RENAME_COMMAND -> ankushRename(args)
            Ankush.RESET_COMMAND -> ankushReset(args)
        }
    }

    private fun startAnkushPrompt(prompt: AnkushPrompt, message: String) {
        ankushPrompt = prompt
        output(message, theme.warningTextColor, null)
        activity.runOnUiThread { binding.cmdInput.setPasswordMode(true) }
    }

    private fun endAnkushPrompt() {
        ankushPrompt = null
        ankushNewPassword = null
        activity.runOnUiThread { binding.cmdInput.setPasswordMode(false) }
    }

    private fun handleAnkushPromptInput(input: String) {
        val value = input.trim()
        when (ankushPrompt) {
            AnkushPrompt.HELP_PASSWORD -> {
                endAnkushPrompt()
                if (Ankush.checkPassword(preferenceObject, value)) {
                    showAnkushHelp()
                } else {
                    output("Wrong password.", theme.errorTextColor, null)
                }
            }
            AnkushPrompt.PASSWD_CURRENT -> {
                if (Ankush.checkPassword(preferenceObject, value)) {
                    ankushPrompt = AnkushPrompt.PASSWD_NEW
                    output("Enter new password:", theme.warningTextColor, null)
                } else {
                    endAnkushPrompt()
                    output("Wrong password.", theme.errorTextColor, null)
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
            null -> {}
        }
    }

    private fun showAnkushHelp() {
        val renames = Ankush.getRenames(preferenceObject)
        output("Ankush command map", theme.warningTextColor, Typeface.BOLD_ITALIC)
        output("-------------------------", theme.warningTextColor, null)
        if (renames.isEmpty()) {
            output("No commands renamed.", theme.resultTextColor, null)
        } else {
            renames.toSortedMap().forEach { (original, custom) ->
                output("$original  ->  $custom   ('$original' disabled)", theme.successTextColor, null)
            }
        }
        output("-------------------------", theme.warningTextColor, null)
        output("Aliases (type: <alias> <hour+minute>, 24h clock)", theme.warningTextColor, Typeface.BOLD)
        if (aliasList.isEmpty()) {
            output("No aliases.", theme.resultTextColor, null)
        } else {
            aliasList.sortedBy { it.key }.forEach {
                output("${it.key}  =  ${it.value}", theme.resultTextColor, null)
            }
        }
        output("-------------------------", theme.warningTextColor, null)
        output("Unchanged commands: " + commands.keys.filter { it !in renames }.sorted().joinToString(", "), theme.resultTextColor, null)
        output("-------------------------", theme.warningTextColor, null)
        output("${Ankush.RENAME_COMMAND} <command> <new-name>  |  ${Ankush.RESET_COMMAND} <command|all>  |  ${Ankush.PASSWD_COMMAND}", theme.resultTextColor, null)
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
