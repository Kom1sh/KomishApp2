package com.example.komishapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import android.graphics.Color
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import org.json.JSONException

data class ChatMessage(
    val id: String = "",
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val senderId: String = ""
)

class MainActivity : AppCompatActivity() {

    private lateinit var nameEditText: EditText
    private lateinit var enterButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        nameEditText = findViewById(R.id.NameEnter)
        enterButton = findViewById(R.id.enter)

        nameEditText.setTextColor(Color.BLACK)
        enterButton.setOnClickListener {
            val name = nameEditText.text.toString().trim()
            if (name.isNotEmpty()) {
                val intent = Intent(this, ChatActivity::class.java).apply {
                    putExtra("USER_NAME", name)
                }
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this, "Пожалуйста, введите ваше имя", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

class ChatAdapter(private val messages: List<ChatMessage>) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val senderTextView: TextView = view.findViewById(R.id.textViewSender)
        val messageTextView: TextView = view.findViewById(R.id.textViewMessage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.chat_layout, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val chatMessage = messages[position]
        holder.senderTextView.text = chatMessage.senderId
        holder.messageTextView.text = chatMessage.message
    }

    override fun getItemCount() = messages.size
}

class ChatActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var editTextMessage: EditText
    private lateinit var buttonSend: Button
    private lateinit var chatAdapter: ChatAdapter
    private val firestore = FirebaseFirestore.getInstance()
    private val messagesCollection = firestore.collection("messages")
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var userName: String
    private val client = OkHttpClient()
    private val apiUrl = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion"
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        recyclerView = findViewById(R.id.recyclerView)
        editTextMessage = findViewById(R.id.editTextMessage)
        buttonSend = findViewById(R.id.buttonSend)

        editTextMessage.setTextColor(Color.BLACK)

        userName = intent.getStringExtra("USER_NAME") ?: "Anonymous"

        chatAdapter = ChatAdapter(messages)
        recyclerView.adapter = chatAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        fetchExistingMessages()

        buttonSend.setOnClickListener {
            val messageText = editTextMessage.text.toString()
            if (messageText.isNotEmpty()) {
                activityScope.launch {
                    try {
                        // Сначала добавляем сообщение пользователя в чат
                        addMessageToChat(messageText, userName)
                        editTextMessage.text.clear()
                        // Затем отправляем запрос и обрабатываем ответ
                        sendRequest(messageText)


                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@ChatActivity,
                                "Ошибка при отправке/записи сообщения: ${e.message}",
                                LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }

        messagesCollection.orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Toast.makeText(this, "Не удается получать новые сообщения", LENGTH_SHORT).show()
                    return@addSnapshotListener
                }
                if (snapshot != null && !snapshot.isEmpty) {
                    messages.clear()
                    for (document in snapshot.documents) {
                        val message = document.toObject(ChatMessage::class.java)
                        message?.let { messages.add(it) }
                    }
                    chatAdapter.notifyDataSetChanged()
                    recyclerView.scrollToPosition(messages.size - 1)
                }
            }
    }

    private suspend fun sendRequest(userText: String) {
        withContext(Dispatchers.IO) {
            val json = JSONObject()
            json.put("modelUri", "gpt://b1g7a9qtot85j5lsb2gc/yandexgpt-lite")
            val completionOptions = JSONObject()
            completionOptions.put("stream", false)
            completionOptions.put("temperature", 0.6)
            completionOptions.put("maxTokens", "2000")
            json.put("completionOptions", completionOptions)
            val messagesArray = org.json.JSONArray()
            val messageObject = JSONObject()
            messageObject.put("role", "user")
            messageObject.put("text", userText)
            messagesArray.put(messageObject)
            json.put("messages", messagesArray)

            val requestBody = json.toString().toRequestBody("application/json".toMediaType())

            // Вставьте ваш API-ключ В ЭТУ СТРОКУ!
            val apiKey = "Api-Key AQVN2GrocpImFrHcArPsgv1kXyzA_eCZHtd2ft9T"
            val request = Request.Builder()
                .url(apiUrl)
                .header("Authorization", apiKey)
                .post(requestBody)
                .build()

            try {
                val client = OkHttpClient().newBuilder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw IOException("Неуспешный запрос: ${response.code} ${response.message}")
                }
                val responseBody = response.body?.string()
                println("Response Body: $responseBody")

                // Пытаемся получить текст ответа из json
                val yandexGPTText = parseYandexGPTResponse(responseBody)
                // Выводим ответ от YandexGPT
                if(yandexGPTText != null){
                    withContext(Dispatchers.Main){
                        addMessageToChat(yandexGPTText, "YandexGPT")
                    }
                }

            } catch(e: Exception) {
                withContext(Dispatchers.Main){
                    Toast.makeText(this@ChatActivity, "Ошибка при отправке запроса: ${e.message}", LENGTH_SHORT).show()
                }
                throw e
            }
        }
    }
    // Функция для добавления сообщения в чат
    private suspend fun addMessageToChat(text: String, sender: String) {
        withContext(Dispatchers.Main) {
            val message = ChatMessage(
                id = messagesCollection.document().id,
                message = text,
                senderId = sender
            )
            messagesCollection.document(message.id).set(message)
        }
    }


    private fun parseYandexGPTResponse(responseBody: String?): String? {
        if (responseBody == null) return null

        try {
            val jsonResponse = JSONObject(responseBody)
            val result = jsonResponse.getJSONObject("result")
            val alternatives = result.getJSONArray("alternatives")
            if (alternatives.length() > 0) {
                val firstAlternative = alternatives.getJSONObject(0)
                val message = firstAlternative.getJSONObject("message")
                return message.getString("text")
            }
        } catch (e: JSONException) {
            println("Ошибка при парсинге JSON: ${e.message}")
        }
        return null
    }


    private fun fetchExistingMessages() {
        messagesCollection.orderBy("timestamp", Query.Direction.ASCENDING)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot != null && !snapshot.isEmpty) {
                    messages.clear()
                    for (document in snapshot.documents) {
                        val message = document.toObject(ChatMessage::class.java)
                        message?.let { messages.add(it) }
                    }
                    chatAdapter.notifyDataSetChanged()
                    recyclerView.scrollToPosition(messages.size - 1)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Не удается получить сообщения", LENGTH_SHORT)
            }
    }
    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}