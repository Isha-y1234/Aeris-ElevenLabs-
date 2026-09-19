package com.runanywhere.kotlin_starter_example.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SettingsRepository {
    private const val PREFS_NAME = "aeris_settings"
    private const val KEY_ADAPTIVE = "adaptive_mode"
    private const val PREFIX_SENSITIVITY = "sensitivity_"
    private const val KEY_NOTIFICATION_ENABLED = "notifications_enabled"
    private const val KEY_FLASH_ENABLED = "flash_alerts_enabled"
    private const val KEY_WALKTHROUGH_COMPLETED = "walkthrough_completed"
    private const val KEY_COMM_WALKTHROUGH_COMPLETED = "comm_walkthrough_completed"
    
    // ElevenLabs Keys
    private const val KEY_USE_ELEVENLABS = "use_elevenlabs"

    // Profile Keys
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_PHONE = "user_phone"
    private const val KEY_USER_EMERGENCY_CONTACT = "emergency_contact"

    private lateinit var prefs: SharedPreferences

    private val _sensitivities = MutableStateFlow<Map<SoundType, Float>>(emptyMap())
    val sensitivities: StateFlow<Map<SoundType, Float>> = _sensitivities

    private val _adaptiveMode = MutableStateFlow(false)
    val adaptiveMode: StateFlow<Boolean> = _adaptiveMode

    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled

    private val _flashEnabled = MutableStateFlow(true)
    val flashEnabled: StateFlow<Boolean> = _flashEnabled

    private val _walkthroughCompleted = MutableStateFlow(false)
    val walkthroughCompleted: StateFlow<Boolean> = _walkthroughCompleted

    private val _commWalkthroughCompleted = MutableStateFlow(false)
    val commWalkthroughCompleted: StateFlow<Boolean> = _commWalkthroughCompleted

    // ElevenLabs Flows - Set default to true so it works out of the box if API key is set
    private val _useElevenLabs = MutableStateFlow(true)
    val useElevenLabs: StateFlow<Boolean> = _useElevenLabs

    // Profile Flows
    private val _userName = MutableStateFlow("")
    val userName: StateFlow<String> = _userName

    private val _userPhone = MutableStateFlow("")
    val userPhone: StateFlow<String> = _userPhone

    private val _emergencyContact = MutableStateFlow("")
    val emergencyContact: StateFlow<String> = _emergencyContact

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _adaptiveMode.value = prefs.getBoolean(KEY_ADAPTIVE, false)
        _notificationsEnabled.value = prefs.getBoolean(KEY_NOTIFICATION_ENABLED, true)
        _flashEnabled.value = prefs.getBoolean(KEY_FLASH_ENABLED, true)
        _walkthroughCompleted.value = prefs.getBoolean(KEY_WALKTHROUGH_COMPLETED, false)
        _commWalkthroughCompleted.value = prefs.getBoolean(KEY_COMM_WALKTHROUGH_COMPLETED, false)
        
        // Changed default to true
        _useElevenLabs.value = prefs.getBoolean(KEY_USE_ELEVENLABS, true)
        
        _userName.value = prefs.getString(KEY_USER_NAME, "") ?: ""
        _userPhone.value = prefs.getString(KEY_USER_PHONE, "") ?: ""
        _emergencyContact.value = prefs.getString(KEY_USER_EMERGENCY_CONTACT, "") ?: ""
        
        val map = SoundType.entries.associateWith { type ->
            prefs.getFloat(PREFIX_SENSITIVITY + type.name, 0.5f)
        }
        _sensitivities.value = map
    }

    fun setSensitivity(type: SoundType, value: Float) {
        prefs.edit().putFloat(PREFIX_SENSITIVITY + type.name, value).apply()
        val current = _sensitivities.value.toMutableMap()
        current[type] = value
        _sensitivities.value = current
    }

    fun setAdaptiveMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ADAPTIVE, enabled).apply()
        _adaptiveMode.value = enabled
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_ENABLED, enabled).apply()
        _notificationsEnabled.value = enabled
    }

    fun setFlashEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FLASH_ENABLED, enabled).apply()
        _flashEnabled.value = enabled
    }

    fun setWalkthroughCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_WALKTHROUGH_COMPLETED, completed).apply()
        _walkthroughCompleted.value = completed
    }

    fun setCommWalkthroughCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_COMM_WALKTHROUGH_COMPLETED, completed).apply()
        _commWalkthroughCompleted.value = completed
    }

    fun setUseElevenLabs(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_ELEVENLABS, enabled).apply()
        _useElevenLabs.value = enabled
    }

    fun setUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name).apply()
        _userName.value = name
    }

    fun setUserPhone(phone: String) {
        prefs.edit().putString(KEY_USER_PHONE, phone).apply()
        _userPhone.value = phone
    }

    fun setEmergencyContact(contact: String) {
        prefs.edit().putString(KEY_USER_EMERGENCY_CONTACT, contact).apply()
        _emergencyContact.value = contact
    }
}
