package github.com

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SimpleAdapter
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.wolfram.alpha.WAEngine
import com.wolfram.alpha.WAPlainText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class MainActivity : AppCompatActivity() {

    private val TAG: String = "MainActivity"
    lateinit var requestInput: TextInputEditText
    lateinit var podsAdapter: SimpleAdapter
    lateinit var progressBar: ProgressBar
    lateinit var waEngine: WAEngine
    private val pods = mutableListOf<HashMap<String, String>>()
    lateinit var textToSpeech: TextToSpeech
    private var TtsIsReady: Boolean = false
    val VOICE_CODE_REQUEST = 777

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_stop -> {
                if (TtsIsReady) {
                    textToSpeech.stop()
                    Log.d(TAG, "Stop")
                }
                return true
            }
            R.id.action_clear -> {
                requestInput.text?.clear()
                pods.clear()
                podsAdapter.notifyDataSetChanged()
                Log.d(TAG, "Clear")
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // для получения ответа от запроса showVoiceDialog
    // переопределяем метод
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_CODE_REQUEST && resultCode == RESULT_OK) {
            // вытаскиваем массив данных
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)?.let { question ->
                requestInput.setText(question)
                askWolframe(question)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initWolframeEngine()
        initViews()
        initTTS()
    }

    private fun initViews() {
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        requestInput = findViewById(R.id.textInputEditText)
        requestInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                pods.clear() // очистка списка
                podsAdapter.notifyDataSetChanged() // встряска адаптера
                val question = requestInput.text.toString()
                askWolframe(question)
            }
            // прячем клавиатуру если нажали на кнопку с помощью FALSE
            return@setOnEditorActionListener false
        }

        val podsList: ListView = findViewById(R.id.podsList)
        podsAdapter = SimpleAdapter(
            applicationContext,
            pods,
            R.layout.item_pod,
            arrayOf("Tittle", "Content"),
            intArrayOf(R.id.tittle, R.id.content)
        )
        podsList.adapter = podsAdapter
        podsList.setOnItemClickListener { parent, view, position, id ->
            if (TtsIsReady) { // если ТТС готов, то вытаскиваем Тайтл и Контент по ключу
                val title = pods[position]["Tittle"]
                val content = pods[position]["Content"]
                // воспроизводим текст
                textToSpeech.speak(content, TextToSpeech.QUEUE_FLUSH, null, title)
            }

        }

        val voiceInputButton: FloatingActionButton = findViewById(R.id.voiceInputButton)
        voiceInputButton.setOnClickListener {
            pods.clear()
            podsAdapter.notifyDataSetChanged()
            if(TtsIsReady) {
                textToSpeech.stop()
            }
            showVoiceDialog()
        }

        progressBar = findViewById(R.id.progressBar)
    }

    private fun initWolframeEngine() {
        waEngine = WAEngine().apply {
            appID = "6984QQ-U8UXLRXA6P"
            addFormat("plaintext")
        }
    }

    private fun showSnackBar(message: String) {
        Snackbar.make(
            findViewById(android.R.id.content),
            message,
            Snackbar.LENGTH_INDEFINITE)
            .apply {
                setAction(android.R.string.ok) {
                    dismiss()
                }
                show()
            }
    }

    private fun askWolframe(request: String) {
        // Отобразили прогрес бар
        progressBar.visibility = View.VISIBLE
        // запускаем второстепенный поток
        CoroutineScope(Dispatchers.IO).launch {
            // переменная которая будет хранить в себе запрос
            val query = waEngine.createQuery().apply {
                // здесь указываем что входит в запрос
                input = request
            }
            // обработчик исключений
            kotlin.runCatching { waEngine.performQuery(query) }
                .onSuccess { // переводим результат на главный поток
                        result ->
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        // обрабатываем запрос
                        if (result.isError) {
                            showSnackBar(result.errorMessage)
                            return@withContext
                        }

                        if (!result.isSuccess) {
                            requestInput.error = getString(R.string.error_do_not_understand)
                            return@withContext
                        }

                        for (pod in result.pods) {
                            if (pod.isError) continue
                            val content = StringBuilder()
                            for (subpod in pod.subpods) {
                                for (element in subpod.contents) {
                                    if (element is WAPlainText) {
                                        content.append(element.text)
                                    }
                                }
                            }
                            // добавленный контент добавляем в список
                            pods.add(0, HashMap<String, String>().apply {
                                put("Tittle", pod.title)
                                put("Content", content.toString())
                            })
                        }

                        // встряхнуть адаптер
                        podsAdapter.notifyDataSetChanged()
                    }
                }
                .onFailure { t ->
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        // покажет что произошла ошибка
                        showSnackBar(t.message ?: getString(R.string.error_something_went_wrong))
                    }
                }
        }
    }

    private fun initTTS() {
        textToSpeech = TextToSpeech(this) { code ->
            if (code != TextToSpeech.SUCCESS) {
                // уведомляем пользователя об ошибке
                Log.e(TAG, "TTS error: $code")
                showSnackBar(getString(R.string.error_tts_is_not_ready))
            } else {
                TtsIsReady = true
            }
        }
        textToSpeech.language = Locale.US
    }

    private fun showVoiceDialog() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // подсказка распознования голоса
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.request_hint))
            // язык
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
        }
        kotlin.runCatching {
            // запрос запуска программы
            startActivityForResult(intent, VOICE_CODE_REQUEST)
        }.onFailure { t ->
            showSnackBar(t.message ?: getString(R.string.error_voice_recognize_unavailable))
        }
    }


}