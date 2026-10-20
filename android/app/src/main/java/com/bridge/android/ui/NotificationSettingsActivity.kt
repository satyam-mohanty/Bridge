package com.bridge.android.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
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
import com.bridge.android.databinding.ActivityNotificationSettingsBinding
import com.bridge.android.databinding.ItemAppNotificationBinding
import com.bridge.android.notification.BridgeNotificationListenerService
import com.bridge.android.notification.NotificationPreferences
import com.bridge.android.util.LogManager
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationSettingsBinding
    private lateinit var prefs: NotificationPreferences

    private val iconCache = object : LruCache<String, Drawable>(120) {}
    private var allAppsList = listOf<AppEntry>()
    private var filteredAppsList = listOf<AppEntry>()
    private var appsAdapter: AppsAdapter? = null

    data class AppEntry(
        val packageName: String,
        val label: String,
        val isSystem: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = NotificationPreferences(this)

        setupUI()
        loadAppsList()
    }

    override fun onResume() {
        super.onResume()
        updateNotificationAccessStatus()
        updateRecentAppsSection()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Master switch
        binding.switchMasterNotifications.isChecked = prefs.isSyncEnabled
        binding.switchMasterNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.isSyncEnabled = isChecked
            LogManager.i("Notifications master switch set to $isChecked")
            if (isChecked && !isNotificationAccessGranted()) {
                openNotificationAccessSettings()
            }
        }

        // Notification Access Row
        binding.rowNotificationAccess.setOnClickListener {
            openNotificationAccessSettings()
        }

        // Show previews switch
        binding.switchShowPreviews.isChecked = prefs.showPreviews
        binding.switchShowPreviews.setOnCheckedChangeListener { _, isChecked ->
            prefs.showPreviews = isChecked
            LogManager.i("Notifications showPreviews set to $isChecked")
        }

        // Show system apps switch
        binding.switchShowSystemApps.isChecked = prefs.showSystemApps
        binding.switchShowSystemApps.setOnCheckedChangeListener { _, isChecked ->
            prefs.showSystemApps = isChecked
            filterAndDisplayApps(binding.etSearchApps.text?.toString() ?: "")
        }

        // Search text watcher
        binding.etSearchApps.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterAndDisplayApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

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
            binding.tvNotificationAccessStatus.text = "Allowed"
            binding.tvNotificationAccessStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            binding.tvNotificationAccessStatus.text = "Not allowed"
            binding.tvNotificationAccessStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
        }
    }

    private fun openNotificationAccessSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    private fun loadAppsList() {
        binding.pbLoadingApps.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val currentPkg = packageName

            val list = mutableListOf<AppEntry>()
            for (info in installed) {
                if (info.packageName == currentPkg) continue
                val label = try {
                    pm.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    info.packageName
                }
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                list.add(AppEntry(info.packageName, label, isSystem))
            }

            // Alphabetical sort by label
            list.sortBy { it.label.lowercase() }

            withContext(Dispatchers.Main) {
                allAppsList = list
                binding.pbLoadingApps.visibility = View.GONE
                filterAndDisplayApps(binding.etSearchApps.text?.toString() ?: "")
                updateRecentAppsSection()
            }
        }
    }

    private fun filterAndDisplayApps(query: String) {
        val showSystem = prefs.showSystemApps
        val q = query.trim().lowercase()

        filteredAppsList = allAppsList.filter { entry ->
            val matchesSystem = showSystem || !entry.isSystem
            val matchesQuery = q.isEmpty() || entry.label.lowercase().contains(q) || entry.packageName.lowercase().contains(q)
            matchesSystem && matchesQuery
        }

        appsAdapter?.notifyDataSetChanged()
    }

    private fun updateRecentAppsSection() {
        val recentPackages = prefs.getRecentPackages()
        binding.containerRecentApps.removeAllViews()

        if (recentPackages.isEmpty()) {
            binding.tvHeaderRecent.visibility = View.GONE
            binding.cardRecentApps.visibility = View.GONE
            return
        }

        val pm = packageManager
        var addedCount = 0

        for (pkg in recentPackages) {
            if (pkg == packageName) continue
            val appInfo = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (_: Exception) {
                continue
            }
            val label = pm.getApplicationLabel(appInfo).toString()

            val itemBinding = ItemAppNotificationBinding.inflate(layoutInflater, binding.containerRecentApps, false)
            itemBinding.tvAppName.text = label
            itemBinding.tvAppPackage.text = pkg

            val isEnabled = prefs.isPackageEnabled(pkg)
            itemBinding.switchAppEnabled.isChecked = isEnabled
            itemBinding.switchAppEnabled.setOnCheckedChangeListener { _, isChecked ->
                prefs.setPackageEnabled(pkg, isChecked)
                appsAdapter?.notifyDataSetChanged()
            }

            loadAppIconAsync(pkg, itemBinding.ivAppIcon)

            binding.containerRecentApps.addView(itemBinding.root)
            addedCount++
            if (addedCount >= 5) break // Show top 5 recent
        }

        if (addedCount > 0) {
            binding.tvHeaderRecent.visibility = View.VISIBLE
            binding.cardRecentApps.visibility = View.VISIBLE
        } else {
            binding.tvHeaderRecent.visibility = View.GONE
            binding.cardRecentApps.visibility = View.GONE
        }
    }

    private fun loadAppIconAsync(packageName: String, imageView: ImageView) {
        val cached = iconCache.get(packageName)
        if (cached != null) {
            imageView.setImageDrawable(cached)
            return
        }

        imageView.setImageDrawable(null)
        lifecycleScope.launch(Dispatchers.IO) {
            val drawable = try {
                packageManager.getApplicationIcon(packageName)
            } catch (_: Exception) {
                null
            }
            if (drawable != null) {
                iconCache.put(packageName, drawable)
                withContext(Dispatchers.Main) {
                    imageView.setImageDrawable(drawable)
                }
            }
        }
    }

    inner class AppsAdapter : RecyclerView.Adapter<AppsAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemAppNotificationBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemAppNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = filteredAppsList[position]
            holder.binding.tvAppName.text = entry.label
            holder.binding.tvAppPackage.text = entry.packageName

            // Clear listener before updating checked state to avoid recursion
            holder.binding.switchAppEnabled.setOnCheckedChangeListener(null)
            holder.binding.switchAppEnabled.isChecked = prefs.isPackageEnabled(entry.packageName)

            holder.binding.switchAppEnabled.setOnCheckedChangeListener { _, isChecked ->
                prefs.setPackageEnabled(entry.packageName, isChecked)
                updateRecentAppsSection()
            }

            holder.binding.root.setOnClickListener {
                holder.binding.switchAppEnabled.toggle()
            }

            loadAppIconAsync(entry.packageName, holder.binding.ivAppIcon)
        }

        override fun getItemCount(): Int = filteredAppsList.size
    }
}
