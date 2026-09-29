package com.example.lecturenotes.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.lecturenotes.databinding.ActivitySettingsBinding
import com.example.lecturenotes.network.ApiKeyStore

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        // Загружаем сохранённые настройки
        binding.editGroqKey.setText(ApiKeyStore.getGroqKey(this))
        binding.editGeminiKey.setText(ApiKeyStore.getOpenRouterKey(this))
        binding.editOpenRouterModel.setText(ApiKeyStore.getOpenRouterModel(this))

        binding.buttonSaveKeys.setOnClickListener {
            val groqKey = binding.editGroqKey.text?.toString()?.trim().orEmpty()
            val openRouterKey = binding.editGeminiKey.text?.toString()?.trim().orEmpty()
            val model = binding.editOpenRouterModel.text?.toString()?.trim().orEmpty()

            // Сохраняем ключи
            ApiKeyStore.setGroqKey(this, groqKey)
            ApiKeyStore.setOpenRouterKey(this, openRouterKey)

            // Сохраняем модель, если указана
            if (model.isNotEmpty()) {
                ApiKeyStore.setOpenRouterModel(this, model)
            }

            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}