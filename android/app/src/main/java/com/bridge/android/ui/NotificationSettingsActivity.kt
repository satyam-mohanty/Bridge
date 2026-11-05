package com.bridge.android.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bridge.android.R
import com.bridge.android.crypto.KeyStoreManager
import com.bridge.android.databinding.ActivityNotificationSettingsBinding
import com.bridge.android.databinding.ItemAppNotificationBinding
import com.bridge.android.notification.NotificationPreferences
import com.bridge.android.util.LogManager
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationSettingsBinding
    private lateinit var prefs: NotificationPreferences
    private lateinit var keyStoreManager: KeyStoreManager

    private val iconCache = object : LruCache<String, Drawable>(120) {}
    private var appsList = listOf<AppEntry>()
    private var appsAdapter: AppsAdapter? = null

    data class AppEntry(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val isEnabled: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = NotificationPreferences(this)
        keyStoreManager = KeyStoreManager(this)

        setupUI()
        loadAppsList()
    }

    override fun onResume() {
        super.onResume()
        updateNotificationAccessStatus()
        updateAppCounts()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnManageAndroidSettings.setOnClickListener {
            openNotificationAccessSettings()
        }

        // Privacy Switches
        // "Hide sensitive content" = !showPreviews
        binding.switchHideSensitive.isChecked = !prefs.showPreviews
        binding.switchHideSensitive.setOnCheckedChangeListener { _, isChecked ->
            prefs.showPreviews = !isChecked
            LogManager.i("Hide sensitive content set to $isChecked (showPreviews=${!isChecked})")
        }

        // "Only when Mac is unlocked"
        binding.switchOnlyWhenMacUnlocked.isChecked = prefs.onlyWhenMacUnlocked
        binding.switchOnlyWhenMacUnlocked.setOnCheckedChangeListener { _, isChecked ->
            prefs.onlyWhenMacUnlocked = isChecked
            LogManager.i("Only when Mac is unlocked set to $isChecked")
        }

        // Mac name in footer
        val macName = keyStoreManager.getMacName() ?: "Mac"
        binding.tvNotifFooterSecurity.text = "Notifications are encrypted and shared only with $macName."

        // RecyclerView
        appsAdapter = AppsAdapter()
        binding.rvAppsList.layoutManager = LinearLayoutManager(this)
        binding.rvAppsList.adapter = appsAdapter
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    private fun updateNotificationAccessStatus() {
        val granted = isNotificationAccessGranted()
        if (granted) {
            binding.ivAccessShield.setColorFilter(ContextCompat.getColor(this, R.color.status_green))
            binding.tvAccessTitle.text = "Notification access enabled"
            binding.tvAccessSubtitle.text = "Bridge can share selected notifications."
        } else {
            binding.ivAccessShield.setColorFilter(ContextCompat.getColor(this, R.color.status_orange))
            binding.tvAccessTitle.text = "Notification access disabled"
            binding.tvAccessSubtitle.text = "Bridge needs notification access to forward notifications to your Mac."
        }
    }

    private fun openNotificationAccessSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    private fun updateAppCounts() {
        val count = prefs.getEnabledPackages().size
        binding.tvSharedAppsCount.text = "$count enabled"
    }

    private fun loadAppsList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val currentPkg = packageName
            val enabledSet = prefs.getEnabledPackages()

            // Common messaging packages to prioritize at top
            val priorityPrefixes = listOf(
                "com.whatsapp",
                "com.google.android.apps.messaging",
                "com.Slack",
                "com.google.android.gm",
                "org.telegram.messenger",
                "com.discord",
                "com.instagram.android",
                "com.twitter.android",
                "com.microsoft.teams"
            )

            val list = mutableListOf<AppEntry>()
            for (info in installed) {
                if (info.packageName == currentPkg) continue
                val hasLaunchIntent = pm.getLaunchIntentForPackage(info.packageName) != null
                val isSystem = !hasLaunchIntent && ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                if (isSystem) continue // Skip internal Android OS processes

                val label = try {
                    pm.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    info.packageName
                }
                list.add(
                    AppEntry(
                        packageName = info.packageName,
                        label = label,
                        isSystem = isSystem,
                        isEnabled = enabledSet.contains(info.packageName)
                    )
                )
            }

            // Sort: Priority apps first, then enabled apps, then alphabetically
            list.sortWith(
                compareByDescending<AppEntry> { entry ->
                    priorityPrefixes.any { entry.packageName.startsWith(it, ignoreCase = true) }
                }
                .thenByDescending { it.isEnabled }
                .thenBy { it.label.lowercase() }
            )

            withContext(Dispatchers.Main) {
                appsList = list
                appsAdapter?.submitList(appsList)
                updateAppCounts()
            }
        }
    }

    inner class AppsAdapter : RecyclerView.Adapter<AppsAdapter.AppViewHolder>() {
        private var items = listOf<AppEntry>()

        fun submitList(newList: List<AppEntry>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val binding = ItemAppNotificationBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return AppViewHolder(binding)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(items[position], isLast = position == items.size - 1)
        }

        override fun getItemCount(): Int = items.size

        inner class AppViewHolder(private val itemBinding: ItemAppNotificationBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(entry: AppEntry, isLast: Boolean) {
                itemBinding.tvAppName.text = entry.label
                itemBinding.dividerLine.visibility = if (isLast) View.GONE else View.VISIBLE

                // Load Icon
                val cached = iconCache.get(entry.packageName)
                if (cached != null) {
                    itemBinding.ivAppIcon.setImageDrawable(cached)
                } else {
                    itemBinding.ivAppIcon.setImageResource(R.drawable.ic_notifications_outline)
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val icon = packageManager.getApplicationIcon(entry.packageName)
                            iconCache.put(entry.packageName, icon)
                            withContext(Dispatchers.Main) {
                                itemBinding.ivAppIcon.setImageDrawable(icon)
                            }
                        } catch (_: Exception) {}
                    }
                }

                itemBinding.switchAppEnabled.setOnCheckedChangeListener(null)
                itemBinding.switchAppEnabled.isChecked = prefs.isPackageEnabled(entry.packageName)

                itemBinding.switchAppEnabled.setOnCheckedChangeListener { _, isChecked ->
                    prefs.setPackageEnabled(entry.packageName, isChecked)
                    updateAppCounts()
                    LogManager.i("App ${entry.label} (${entry.packageName}) notification sharing set to $isChecked")
                }
            }
        }
    }
}
