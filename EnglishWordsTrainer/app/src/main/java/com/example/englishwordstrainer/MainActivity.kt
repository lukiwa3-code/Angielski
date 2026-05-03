package com.example.englishwordstrainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

private const val DEFAULT_GITHUB_INDEX_URL =
    "https://raw.githubusercontent.com/lukiwa3-code/Angielski/main/index.txt"

enum class SourceMode {
    GITHUB,
    FOLDER
}

data class AppSettings(
    val caseSensitive: Boolean = false,
    val acceptTypos: Boolean = true,
    val sourceMode: SourceMode = SourceMode.GITHUB,
    val githubIndexUrl: String = DEFAULT_GITHUB_INDEX_URL,
    val folderUri: String = ""
)

data class WordEntry(
    val polish: String,
    val english: String
) {
    val key: String
        get() = "${polish.trim().lowercase(Locale.ROOT)}::${english.trim().lowercase(Locale.ROOT)}"
}

data class DictionaryBundle(
    val id: String,
    val name: String,
    val words: List<WordEntry>,
    val isCreative: Boolean = false
)

data class TestWrongAnswer(
    val word: WordEntry,
    val userAnswer: String
)

sealed class AppScreen {
    data object Home : AppScreen()
    data object Settings : AppScreen()
    data object TestSetup : AppScreen()
    data class Learn(val dictionary: DictionaryBundle) : AppScreen()
    data class TestRun(val dictionaries: List<DictionaryBundle>) : AppScreen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                WordTrainerApp()
            }
        }
    }
}

class VocabularyStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("vocabulary_store", Context.MODE_PRIVATE)

    fun loadSettings(): AppSettings {
        val modeName = prefs.getString("sourceMode", SourceMode.GITHUB.name)
            ?: SourceMode.GITHUB.name

        val savedGithubIndexUrl = prefs.getString("githubIndexUrl", null)
            ?.trim()
            .orEmpty()
            .ifBlank {
                DEFAULT_GITHUB_INDEX_URL
            }

        return AppSettings(
            caseSensitive = prefs.getBoolean("caseSensitive", false),
            acceptTypos = prefs.getBoolean("acceptTypos", true),
            sourceMode = runCatching {
                SourceMode.valueOf(modeName)
            }.getOrDefault(SourceMode.GITHUB),
            githubIndexUrl = savedGithubIndexUrl,
            folderUri = prefs.getString("folderUri", "") ?: ""
        )
    }

    fun saveSettings(settings: AppSettings) {
        val fixedSettings = settings.copy(
            githubIndexUrl = settings.githubIndexUrl.trim().ifBlank {
                DEFAULT_GITHUB_INDEX_URL
            }
        )

        prefs.edit()
            .putBoolean("caseSensitive", fixedSettings.caseSensitive)
            .putBoolean("acceptTypos", fixedSettings.acceptTypos)
            .putString("sourceMode", fixedSettings.sourceMode.name)
            .putString("githubIndexUrl", fixedSettings.githubIndexUrl)
            .putString("folderUri", fixedSettings.folderUri)
            .apply()
    }

    fun loadCompletedDictionaries(): Map<String, String> {
        val raw = prefs.getString("completedDictionaries", "{}") ?: "{}"
        val result = mutableMapOf<String, String>()

        runCatching {
            val json = JSONObject(raw)
            val keys = json.keys()

            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key).trim()

                if (key.isNotBlank() && value.isNotBlank()) {
                    result[key] = value
                }
            }
        }

        return result
    }

    fun markDictionaryCompleted(dictionary: DictionaryBundle) {
        val currentRaw = prefs.getString("completedDictionaries", "{}") ?: "{}"

        val json = runCatching {
            JSONObject(currentRaw)
        }.getOrElse {
            JSONObject()
        }

        val completedAt = SimpleDateFormat(
            "yyyy-MM-dd HH:mm",
            Locale.getDefault()
        ).format(Date())

        json.put(dictionary.id, completedAt)

        prefs.edit()
            .putString("completedDictionaries", json.toString())
            .apply()
    }

    fun loadCreativeWords(): List<WordEntry> {
        val raw = prefs.getString("creativeWords", "[]") ?: "[]"
        val result = mutableListOf<WordEntry>()

        runCatching {
            val array = JSONArray(raw)

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                val polish = item.optString("polish").trim()
                val english = item.optString("english").trim()

                if (polish.isNotBlank() && english.isNotBlank()) {
                    result += WordEntry(
                        polish = polish,
                        english = english
                    )
                }
            }
        }

        return result.distinctBy { it.key }
    }

    fun addWrongWord(word: WordEntry) {
        val current = loadCreativeWords().toMutableList()

        if (current.none { it.key == word.key }) {
            current += word
            saveCreativeWords(current)
        }
    }

    fun removeCreativeWord(word: WordEntry) {
        val current = loadCreativeWords()
            .filterNot { it.key == word.key }

        saveCreativeWords(current)
    }

    private fun saveCreativeWords(words: List<WordEntry>) {
        val array = JSONArray()

        words.distinctBy { it.key }.forEach { word ->
            val item = JSONObject()
            item.put("polish", word.polish)
            item.put("english", word.english)
            array.put(item)
        }

        prefs.edit()
            .putString("creativeWords", array.toString())
            .apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordTrainerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember {
        VocabularyStore(context.applicationContext)
    }

    var screen by remember {
        mutableStateOf<AppScreen>(AppScreen.Home)
    }

    var settings by remember {
        mutableStateOf(store.loadSettings())
    }

    var dictionaries by remember {
        mutableStateOf<List<DictionaryBundle>>(emptyList())
    }

    var loading by remember {
        mutableStateOf(false)
    }

    var status by remember {
        mutableStateOf("")
    }
    var creativeRefresh by remember {
    mutableIntStateOf(0)
    }
    var completedDictionaries by remember {
    mutableStateOf(store.loadCompletedDictionaries())
    }
    var dictionaryLoadJob by remember {
    mutableStateOf<Job?>(null)
}

fun reloadDictionariesWith(
    newSettings: AppSettings,
    allowDuringLearning: Boolean = false
) {
    dictionaryLoadJob?.cancel()

    dictionaryLoadJob = scope.launch {
        val canStartReload =
            allowDuringLearning ||
                screen == AppScreen.Home ||
                screen == AppScreen.Settings

        if (!canStartReload) {
            return@launch
        }

        loading = true
        status = "Ładowanie słowników..."

        val fixedSettings = newSettings.copy(
            githubIndexUrl = newSettings.githubIndexUrl.trim().ifBlank {
                DEFAULT_GITHUB_INDEX_URL
            }
        )

        val result = runCatching {
            loadDictionaries(
                context = context,
                settings = fixedSettings
            )
        }

        if (result.exceptionOrNull() is CancellationException) {
            loading = false
            return@launch
        }

        val canUpdateUi =
            allowDuringLearning ||
                screen == AppScreen.Home ||
                screen == AppScreen.Settings

        if (!canUpdateUi) {
            loading = false
            return@launch
        }

        result
            .onSuccess { loadedDictionaries ->
                dictionaries = loadedDictionaries

                status = if (loadedDictionaries.isEmpty()) {
                    when (fixedSettings.sourceMode) {
                        SourceMode.GITHUB -> {
                            "Nie znaleziono słowników. Sprawdź link RAW do index.txt."
                        }

                        SourceMode.FOLDER -> {
                            "Nie znaleziono plików .txt w wybranym folderze."
                        }
                    }
                } else {
                    "Załadowano słowników: ${loadedDictionaries.size}"
                }
            }
            .onFailure { error ->
                dictionaries = emptyList()

                status = buildString {
                    append("Nie udało się załadować słowników. ")

                    when (fixedSettings.sourceMode) {
                        SourceMode.GITHUB -> {
                            append("Sprawdź, czy link prowadzi do RAW index.txt. ")
                        }

                        SourceMode.FOLDER -> {
                            append("Sprawdź wybrany folder. ")
                        }
                    }

                    append("Błąd: ")
                    append(error.message ?: error::class.java.simpleName)
                }
            }

        loading = false
    }
}

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }

            val newSettings = settings.copy(
                sourceMode = SourceMode.FOLDER,
                folderUri = uri.toString()
            )

            settings = newSettings
            store.saveSettings(newSettings)
            reloadDictionariesWith(newSettings)
        }
    }

    LaunchedEffect(Unit) {
        reloadDictionariesWith(settings)
    }

    val creativeDictionaries = remember(creativeRefresh) {
        makeCreativeDictionaries(store.loadCreativeWords())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Nauka słówek EN")
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (val currentScreen = screen) {
                AppScreen.Home -> HomeScreen(
                    dictionaries = dictionaries,
                    creativeDictionaries = creativeDictionaries,
                    completedDictionaries = completedDictionaries,
                    loading = loading,
                    status = status,
                    onReload = {
                        reloadDictionariesWith(settings)
                    },
                    onSettings = {
                        screen = AppScreen.Settings
                    },
                    onLearn = { dictionary ->
    dictionaryLoadJob?.cancel()
    loading = false
    status = ""
    screen = AppScreen.Learn(dictionary)
},
                    onTest = {
                        screen = AppScreen.TestSetup
                    }
                )

                AppScreen.Settings -> SettingsScreen(
                    settings = settings,
                    onBack = {
                        screen = AppScreen.Home
                    },
                    onSave = { newSettings ->
                        val fixedSettings = newSettings.copy(
                            githubIndexUrl = newSettings.githubIndexUrl.trim().ifBlank {
                                DEFAULT_GITHUB_INDEX_URL
                            }
                        )

                        settings = fixedSettings
                        store.saveSettings(fixedSettings)
                        screen = AppScreen.Home
                        reloadDictionariesWith(fixedSettings)
                    },
                    onPickFolder = {
                        folderPicker.launch(null)
                    }
                )

is AppScreen.Learn -> LearningScreen(
    dictionary = currentScreen.dictionary,
    settings = settings,
    store = store,
    onExit = {
        completedDictionaries = store.loadCompletedDictionaries()
        creativeRefresh++
        screen = AppScreen.Home
    },
    onCreativeChanged = {
        creativeRefresh++
    },
    onDictionaryCompleted = {
        completedDictionaries = store.loadCompletedDictionaries()
    }
)

                AppScreen.TestSetup -> TestSetupScreen(
                    dictionaries = dictionaries,
                    creativeDictionaries = creativeDictionaries,
                    onBack = {
                        screen = AppScreen.Home
                    },
                    onStart = { selectedDictionaries ->
    dictionaryLoadJob?.cancel()
    loading = false
    status = ""
    screen = AppScreen.TestRun(selectedDictionaries)
}
                )

                is AppScreen.TestRun -> TestRunScreen(
                    dictionaries = currentScreen.dictionaries,
                    settings = settings,
                    store = store,
                    onExit = {
                        creativeRefresh++
                        screen = AppScreen.Home
                    }
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    dictionaries: List<DictionaryBundle>,
    creativeDictionaries: List<DictionaryBundle>,
    completedDictionaries: Map<String, String>,
    loading: Boolean,
    status: String,
    onReload: () -> Unit,
    onSettings: () -> Unit,
    onLearn: (DictionaryBundle) -> Unit,
    onTest: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onReload) {
                    Text("Odśwież słowniki")
                }

                OutlinedButton(onClick = onSettings) {
                    Text("Ustawienia")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onTest,
                enabled = dictionaries.isNotEmpty() || creativeDictionaries.isNotEmpty()
            ) {
                Text("Tryb testu")
            }

            if (loading) {
                Spacer(modifier = Modifier.height(8.dp))

                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (status.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(status)
            }
        }

        item {
            SectionTitle("Słowniki główne")
        }

        if (dictionaries.isEmpty()) {
            item {
                Text("Brak załadowanych słowników. Ustaw źródło w ustawieniach.")
            }
        } else {
            items(dictionaries) { dictionary ->
                DictionaryCard(
                    dictionary = dictionary,
                    completionDate = completedDictionaries[dictionary.id],
                    onLearn = {
                        onLearn(dictionary)
                    }
                )
            }
        }



        item {
            SectionTitle("Kreatywne słowniki błędów")
        }

        if (creativeDictionaries.isEmpty()) {
            item {
                Text("Na razie brak błędnie odpowiedzianych słówek.")
            }
        } else {
            items(creativeDictionaries) { dictionary ->
    DictionaryCard(
        dictionary = dictionary,
        completionDate = completedDictionaries[dictionary.id],
        onLearn = {
            onLearn(dictionary)
        }
    )
}
        }
    }
}

@Composable
fun DictionaryCard(
    dictionary: DictionaryBundle,
    completionDate: String?,
    onLearn: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = dictionary.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text("Liczba słówek: ${dictionary.words.size}")

            if (!completionDate.isNullOrBlank()) {
                Text(
                    text = "Zaliczone ✔️",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text("Data zaliczenia: $completionDate")
            }

            Button(
                onClick = onLearn,
                enabled = dictionary.words.isNotEmpty()
            ) {
                Text(
                    if (completionDate.isNullOrBlank()) {
                        "Rozpocznij naukę"
                    } else {
                        "Powtórz naukę"
                    }
                )
            }
        }
    }
}
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onSave: (AppSettings) -> Unit,
    onPickFolder: () -> Unit
) {
    var caseSensitive by remember {
        mutableStateOf(settings.caseSensitive)
    }

    var acceptTypos by remember {
        mutableStateOf(settings.acceptTypos)
    }

    var sourceMode by remember {
        mutableStateOf(settings.sourceMode)
    }

var githubIndexUrl by remember {
    mutableStateOf(
        settings.githubIndexUrl.trim().ifBlank {
            DEFAULT_GITHUB_INDEX_URL
        }
    )
}

    var folderUri by remember {
        mutableStateOf(settings.folderUri)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionTitle("Ustawienia sprawdzania odpowiedzi")
        }

        item {
            SettingSwitchRow(
                title = "Duże i małe litery mają znaczenie",
                checked = caseSensitive,
                onCheckedChange = {
                    caseSensitive = it
                }
            )
        }

        item {
            SettingSwitchRow(
                title = "Akceptuj pojedyncze literówki",
                checked = acceptTypos,
                onCheckedChange = {
                    acceptTypos = it
                }
            )
        }

        item {
            Divider()
            SectionTitle("Źródło słowników")
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = sourceMode == SourceMode.GITHUB,
                    onClick = {
                        sourceMode = SourceMode.GITHUB
                    }
                )

                Text("GitHub / raw URL")
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = sourceMode == SourceMode.FOLDER,
                    onClick = {
                        sourceMode = SourceMode.FOLDER
                    }
                )

                Text("Folder w telefonie")
            }
        }

        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = githubIndexUrl,
                onValueChange = {
                    githubIndexUrl = it
                    sourceMode = SourceMode.GITHUB
                },
                label = {
                    Text("URL do index.txt na GitHub/raw")
                },
                placeholder = {
    Text(DEFAULT_GITHUB_INDEX_URL)
}
            )
        }

        item {
            Text("Folder telefonu:")

            Text(
                text = folderUri.ifBlank {
                    "Nie wybrano folderu"
                },
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    sourceMode = SourceMode.FOLDER
                    onPickFolder()
                }
            ) {
                Text("Wybierz folder")
            }
        }

        item {
            Divider()

            Text(
                text = "Format słownika: polskie_słówko:english_word",
                fontWeight = FontWeight.Bold
            )

            Text("Przykład:")
            Text("niebo:heaven\njabłko:apple\npies:dog")
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onSave(
                            AppSettings(
                                caseSensitive = caseSensitive,
                                acceptTypos = acceptTypos,
                                sourceMode = sourceMode,
                                githubIndexUrl = githubIndexUrl.trim().ifBlank {
                                    DEFAULT_GITHUB_INDEX_URL
                                },
                                folderUri = folderUri.trim()
                            )
                        )
                    }
                ) {
                    Text("Zapisz")
                }

                OutlinedButton(onClick = onBack) {
                    Text("Wróć")
                }
            }
        }
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = title
        )

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LearningScreen(
    dictionary: DictionaryBundle,
    settings: AppSettings,
    store: VocabularyStore,
    onExit: () -> Unit,
    onCreativeChanged: () -> Unit,
    onDictionaryCompleted: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val answerBringIntoViewRequester = remember {
        BringIntoViewRequester()
    }

    val streaks: SnapshotStateMap<String, Int> = remember(dictionary.id) {
        mutableStateMapOf<String, Int>().apply {
            dictionary.words.forEach { word ->
                this[word.key] = 0
            }
        }
    }

    val hiddenWords: SnapshotStateMap<String, Boolean> = remember(dictionary.id) {
        mutableStateMapOf()
    }

    var seriesNumber by remember(dictionary.id) {
        mutableIntStateOf(1)
    }

    var roundWords by remember(dictionary.id) {
        mutableStateOf(dictionary.words.shuffled())
    }

    var currentIndex by remember(dictionary.id) {
        mutableIntStateOf(0)
    }

    var answer by remember(dictionary.id) {
        mutableStateOf("")
    }

    var message by remember(dictionary.id) {
        mutableStateOf("")
    }

    var completed by remember(dictionary.id) {
        mutableStateOf(dictionary.words.isEmpty())
    }

    fun masteredCount(): Int {
        return dictionary.words.count { word ->
            (streaks[word.key] ?: 0) >= 3
        }
    }

    fun hiddenCount(): Int {
        return dictionary.words.count { word ->
            hiddenWords[word.key] == true
        }
    }

    fun activeWords(): List<WordEntry> {
    return dictionary.words.filter { word ->
        val streak = streaks[word.key] ?: 0
        val hidden = hiddenWords[word.key] == true

        streak < 3 && !hidden
    }
}

fun isFullyMastered(): Boolean {
    return dictionary.words.isNotEmpty() && dictionary.words.all { word ->
        (streaks[word.key] ?: 0) >= 3
    }
}

    fun advance() {
        answer = ""

        val nextIndex = currentIndex + 1

        if (nextIndex < roundWords.size) {
            currentIndex = nextIndex
            return
        }

        val nextActiveWords = activeWords()

       if (nextActiveWords.isEmpty()) {
    if (isFullyMastered()) {
        store.markDictionaryCompleted(dictionary)
        onDictionaryCompleted()
    }

    completed = true
    return
}

        seriesNumber++
        roundWords = nextActiveWords.shuffled()
        currentIndex = 0
    }

    fun submitAnswer() {
        if (completed || roundWords.isEmpty()) {
            return
        }

        val word = roundWords[currentIndex]

        val correct = isAnswerCorrect(
            userAnswer = answer,
            expectedAnswer = word.english,
            settings = settings
        )

        if (correct) {
            streaks[word.key] = (streaks[word.key] ?: 0) + 1
            message = "Dobrze ✔️ Licznik: ${streaks[word.key]}/3"
        } else {
            streaks[word.key] = 0
            store.addWrongWord(word)
            onCreativeChanged()
            message = "Źle ❗ Poprawna odpowiedź: ${word.english}. Licznik zresetowany."
        }

        advance()
    }

    fun hideCurrentWord() {
        if (completed || roundWords.isEmpty()) {
            return
        }

        val word = roundWords[currentIndex]
        hiddenWords[word.key] = true

        if (dictionary.isCreative) {
            store.removeCreativeWord(word)
            onCreativeChanged()
            message = "Usunięto ze słownika kreatywnego."
        } else {
            message = "Słówko nie będzie już pokazywane w tej nauce."
        }

        advance()
    }

    val total = dictionary.words.size
    val mastered = masteredCount()
    val hidden = hiddenCount()
    val remaining = total - mastered - hidden

    if (completed) {
        ResultCard(
            title = "Nauka zakończona ✔️",
            body = "Wszystkie aktywne słówka zostały opanowane albo pominięte.",
            onExit = onExit
        )

        return
    }

    val currentWord = roundWords.getOrNull(currentIndex)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = dictionary.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text("Seria: $seriesNumber")
            Text("Słówka do nauki: $total")
            Text("Opanowane: $mastered")
            Text("Do opanowania: $remaining")
            Text("Pominięte: $hidden")

            Spacer(modifier = Modifier.height(8.dp))

            val progress = if (total == 0) {
                0f
            } else {
                (mastered + hidden).toFloat() / total.toFloat()
            }

            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                progress = {
                    progress
                }
            )
        }

        if (currentWord != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Przetłumacz na angielski:")

                        Text(
                            text = currentWord.polish,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text("Aktualny licznik: ${streaks[currentWord.key] ?: 0}/3")
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(answerBringIntoViewRequester),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    scope.launch {
                                        delay(300)
                                        answerBringIntoViewRequester.bringIntoView()
                                    }
                                }
                            },
                        value = answer,
                        onValueChange = {
                            answer = it

                            scope.launch {
                                answerBringIntoViewRequester.bringIntoView()
                            }
                        },
                        singleLine = true,
                        label = {
                            Text("Twoja odpowiedź")
                        }
                    )

                    OutlinedButton(
                        onClick = {
                            hideCurrentWord()
                        }
                    ) {
                        Text("Nie pokazuj więcej")
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        submitAnswer()
                    },
                    enabled = answer.isNotBlank()
                ) {
                    Text("Sprawdź")
                }
            }
        }

        if (message.isNotBlank()) {
            item {
                Text(message)
            }
        }

        item {
            OutlinedButton(onClick = onExit) {
                Text("Zakończ")
            }
        }
    }
}

@Composable
fun TestSetupScreen(
    dictionaries: List<DictionaryBundle>,
    creativeDictionaries: List<DictionaryBundle>,
    onBack: () -> Unit,
    onStart: (List<DictionaryBundle>) -> Unit
) {
    val allDictionaries = dictionaries + creativeDictionaries

    val selected = remember {
        mutableStateMapOf<String, Boolean>()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionTitle("Tryb testu")
            Text("Zaznacz słowniki, z których aplikacja wylosuje 20 słówek.")
        }

        if (allDictionaries.isEmpty()) {
            item {
                Text("Brak dostępnych słowników.")
            }
        } else {
            items(allDictionaries) { dictionary ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selected[dictionary.id] == true,
                        onCheckedChange = {
                            selected[dictionary.id] = it
                        }
                    )

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = dictionary.name,
                            fontWeight = FontWeight.Bold
                        )

                        Text("Słówka: ${dictionary.words.size}")
                    }
                }
            }
        }

        item {
            val selectedDictionaries = allDictionaries.filter { dictionary ->
                selected[dictionary.id] == true
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onStart(selectedDictionaries)
                    },
                    enabled = selectedDictionaries.isNotEmpty()
                ) {
                    Text("Start testu")
                }

                OutlinedButton(onClick = onBack) {
                    Text("Wróć")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TestRunScreen(
    dictionaries: List<DictionaryBundle>,
    settings: AppSettings,
    store: VocabularyStore,
    onExit: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val answerBringIntoViewRequester = remember {
        BringIntoViewRequester()
    }

    val testWords = remember(dictionaries) {
        dictionaries
            .flatMap { dictionary ->
                dictionary.words
            }
            .distinctBy { word ->
                word.key
            }
            .shuffled()
            .take(20)
    }

    var currentIndex by remember {
        mutableIntStateOf(0)
    }

    var answer by remember {
        mutableStateOf("")
    }

    var correctCount by remember {
        mutableIntStateOf(0)
    }

    val wrongAnswers = remember {
        mutableStateListOf<TestWrongAnswer>()
    }

    val finished = currentIndex >= testWords.size

    fun submit() {
        val word = testWords.getOrNull(currentIndex) ?: return

        val correct = isAnswerCorrect(
            userAnswer = answer,
            expectedAnswer = word.english,
            settings = settings
        )

        if (correct) {
            correctCount++
        } else {
            wrongAnswers += TestWrongAnswer(
                word = word,
                userAnswer = answer
            )

            store.addWrongWord(word)
        }

        answer = ""
        currentIndex++
    }

    if (testWords.isEmpty()) {
        ResultCard(
            title = "Brak słówek",
            body = "Wybrane słowniki nie zawierają słówek.",
            onExit = onExit
        )

        return
    }

    if (finished) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Wynik testu",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Text("Poprawne odpowiedzi: $correctCount")
                Text("Błędne odpowiedzi: ${wrongAnswers.size}")
                Text("Łącznie: ${testWords.size}")
            }

            if (wrongAnswers.isNotEmpty()) {
                item {
                    SectionTitle("Błędne odpowiedzi")
                }

                items(wrongAnswers) { wrong ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("PL: ${wrong.word.polish}")
                            Text("Twoja odpowiedź: ${wrong.userAnswer}")
                            Text("Poprawnie: ${wrong.word.english}")
                        }
                    }
                }
            }

            item {
                Button(onClick = onExit) {
                    Text("Zakończ")
                }
            }
        }

        return
    }

    val currentWord = testWords[currentIndex]

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Test",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text("Pytanie ${currentIndex + 1} z ${testWords.size}")
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Przetłumacz na angielski:")

                    Text(
                        text = currentWord.polish,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(answerBringIntoViewRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            scope.launch {
                                delay(300)
                                answerBringIntoViewRequester.bringIntoView()
                            }
                        }
                    },
                value = answer,
                onValueChange = {
                    answer = it

                    scope.launch {
                        answerBringIntoViewRequester.bringIntoView()
                    }
                },
                singleLine = true,
                label = {
                    Text("Twoja odpowiedź")
                }
            )
        }

        item {
            Button(
                onClick = {
                    submit()
                },
                enabled = answer.isNotBlank()
            ) {
                Text("Dalej")
            }
        }

        item {
            OutlinedButton(onClick = onExit) {
                Text("Przerwij test")
            }
        }
    }
}

@Composable
fun ResultCard(
    title: String,
    body: String,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(body)

                Button(onClick = onExit) {
                    Text("Wróć")
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

fun makeCreativeDictionaries(words: List<WordEntry>): List<DictionaryBundle> {
    return words
        .distinctBy { word ->
            word.key
        }
        .chunked(20)
        .mapIndexed { index, chunk ->
            DictionaryBundle(
                id = "creative_$index",
                name = "Kreatywny słownik ${index + 1}",
                words = chunk,
                isCreative = true
            )
        }
}

suspend fun loadDictionaries(
    context: Context,
    settings: AppSettings
): List<DictionaryBundle> {
    return withContext(Dispatchers.IO) {
        when (settings.sourceMode) {
            SourceMode.GITHUB -> {
                val indexUrl = settings.githubIndexUrl.trim().ifBlank {
                    DEFAULT_GITHUB_INDEX_URL
                }

                loadGithubDictionaries(indexUrl)
            }

            SourceMode.FOLDER -> {
                if (settings.folderUri.isBlank()) {
                    emptyList()
                } else {
                    loadFolderDictionaries(
                        context = context,
                        folderUri = Uri.parse(settings.folderUri)
                    )
                }
            }
        }
    }
}

fun demoDictionaries(): List<DictionaryBundle> {
    val fruits = """
        jabłko:apple
        banan:banana
        pomarańcza:orange
        gruszka:pear
        truskawka:strawberry
        winogrono:grape
        cytryna:lemon
        arbuz:watermelon
        brzoskwinia:peach
        śliwka:plum
    """.trimIndent()

    val animals = """
        pies:dog
        kot:cat
        koń:horse
        krowa:cow
        owca:sheep
        koza:goat
        ptak:bird
        ryba:fish
        lew:lion
        tygrys:tiger
    """.trimIndent()

    return listOf(
        DictionaryBundle(
            id = "demo_fruits",
            name = "Demo: owoce",
            words = parseDictionaryText(fruits)
        ),
        DictionaryBundle(
            id = "demo_animals",
            name = "Demo: zwierzęta",
            words = parseDictionaryText(animals)
        )
    )
}

fun loadGithubDictionaries(indexUrl: String): List<DictionaryBundle> {
    val indexText = downloadText(indexUrl)
    val entries = parseGithubIndex(indexText)

    return entries.mapNotNull { entry ->
        runCatching {
            val text = downloadText(entry.second)

            DictionaryBundle(
                id = "github_${entry.first}",
                name = entry.first,
                words = parseDictionaryText(text)
            )
        }.getOrNull()
    }.filter { dictionary ->
        dictionary.words.isNotEmpty()
    }
}

fun parseGithubIndex(text: String): List<Pair<String, String>> {
    return text
        .lineSequence()
        .map { line ->
            line.trim()
        }
        .filter { line ->
            line.isNotBlank() && !line.startsWith("#")
        }
        .mapNotNull { line ->
            val separatorIndex = when {
                line.contains("|") -> line.indexOf("|")
                line.contains("=") -> line.indexOf("=")
                else -> -1
            }

            if (separatorIndex > 0) {
                val name = line.substring(0, separatorIndex).trim()
                val url = line.substring(separatorIndex + 1).trim()

                if (name.isNotBlank() && url.isNotBlank()) {
                    name to url
                } else {
                    null
                }
            } else {
                val url = line.trim()

                val name = url
                    .substringAfterLast("/")
                    .substringBeforeLast(".")
                    .ifBlank {
                        "słownik"
                    }

                if (url.startsWith("http")) {
                    name to url
                } else {
                    null
                }
            }
        }
        .toList()
}

fun downloadText(url: String): String {
    val trimmedUrl = url.trim()

    require(trimmedUrl.startsWith("https://raw.githubusercontent.com/")) {
        "Link musi być RAW z raw.githubusercontent.com"
    }

    val connection = URL(trimmedUrl).openConnection() as HttpURLConnection

    connection.requestMethod = "GET"
    connection.connectTimeout = 12_000
    connection.readTimeout = 12_000

    return try {
        val code = connection.responseCode

        if (code !in 200..299) {
            error("HTTP $code dla adresu: $trimmedUrl")
        }

        BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
            reader.readText()
        }
    } finally {
        connection.disconnect()
    }
}

fun loadFolderDictionaries(
    context: Context,
    folderUri: Uri
): List<DictionaryBundle> {
    val root = DocumentFile.fromTreeUri(context, folderUri)
        ?: return emptyList()

    return root
        .listFiles()
        .filter { file ->
            file.isFile && file.name.orEmpty().endsWith(".txt", ignoreCase = true)
        }
        .mapNotNull { file ->
            val name = file.name
                ?.substringBeforeLast(".")
                ?.ifBlank {
                    "słownik"
                }
                ?: "słownik"

            val text = context.contentResolver
                .openInputStream(file.uri)
                ?.bufferedReader()
                ?.use { reader ->
                    reader.readText()
                }
                ?: return@mapNotNull null

            val words = parseDictionaryText(text)

            if (words.isEmpty()) {
                null
            } else {
                DictionaryBundle(
                    id = "folder_${file.uri}",
                    name = name,
                    words = words
                )
            }
        }
}

fun parseDictionaryText(text: String): List<WordEntry> {
    return text
        .lineSequence()
        .map { line ->
            line.trim()
        }
        .filter { line ->
            line.isNotBlank() && !line.startsWith("#")
        }
        .mapNotNull { line ->
            val separator = line.indexOf(":")

            if (separator <= 0) {
                null
            } else {
                val polish = line.substring(0, separator).trim()
                val english = line.substring(separator + 1).trim()

                if (polish.isBlank() || english.isBlank()) {
                    null
                } else {
                    WordEntry(
                        polish = polish,
                        english = english
                    )
                }
            }
        }
        .distinctBy { word ->
            word.key
        }
        .toList()
}

fun isAnswerCorrect(
    userAnswer: String,
    expectedAnswer: String,
    settings: AppSettings
): Boolean {
    val normalizedUser = normalizeAnswer(
        value = userAnswer,
        caseSensitive = settings.caseSensitive
    )

    val normalizedExpected = normalizeAnswer(
        value = expectedAnswer,
        caseSensitive = settings.caseSensitive
    )

    if (normalizedUser.isBlank() || normalizedExpected.isBlank()) {
        return false
    }

    if (normalizedUser == normalizedExpected) {
        return true
    }

    if (!settings.acceptTypos) {
        return false
    }

    return levenshteinDistance(
        first = normalizedUser,
        second = normalizedExpected
    ) <= 1
}

fun normalizeAnswer(
    value: String,
    caseSensitive: Boolean
): String {
    val trimmed = value
        .trim()
        .replace(Regex("\\s+"), " ")

    return if (caseSensitive) {
        trimmed
    } else {
        trimmed.lowercase(Locale.ROOT)
    }
}

fun levenshteinDistance(
    first: String,
    second: String
): Int {
    if (first == second) {
        return 0
    }

    if (first.isEmpty()) {
        return second.length
    }

    if (second.isEmpty()) {
        return first.length
    }

    val previous = IntArray(second.length + 1) { index ->
        index
    }

    val current = IntArray(second.length + 1)

    for (i in 1..first.length) {
        current[0] = i

        for (j in 1..second.length) {
            val cost = if (first[i - 1] == second[j - 1]) {
                0
            } else {
                1
            }

            current[j] = min(
                min(
                    current[j - 1] + 1,
                    previous[j] + 1
                ),
                previous[j - 1] + cost
            )
        }

        for (j in previous.indices) {
            previous[j] = current[j]
        }
    }

    return previous[second.length]
}


