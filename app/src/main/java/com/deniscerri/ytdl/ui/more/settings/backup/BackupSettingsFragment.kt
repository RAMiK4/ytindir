package com.deniscerri.ytdl.ui.more.settings.backup

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.models.RestoreAppDataItem
import com.deniscerri.ytdl.database.viewmodel.SettingsViewModel
import com.deniscerri.ytdl.ui.more.settings.BaseSettingsFragment
import com.deniscerri.ytdl.util.BackupSettingsUtil
import com.deniscerri.ytdl.util.FileUtil
import com.deniscerri.ytdl.util.ThemeUtil
import com.deniscerri.ytdl.util.UiUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** YTindir: backup and restore, a settings section of its own (was a group on the main settings screen) */
class BackupSettingsFragment : BaseSettingsFragment() {
    override val title: Int = R.string.backup_restore

    private var backup : Preference? = null
    private var restore : Preference? = null
    private var backupPath : Preference? = null

    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var editor: SharedPreferences.Editor

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.backup_preferences, rootKey)
        editor = PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

        backup = findPreference("backup")
        restore = findPreference("restore")
        backupPath = findPreference("backup_path")

        backup!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                val builder = MaterialAlertDialogBuilder(requireContext())
                builder.setTitle(getString(R.string.select_backup_categories))
                val values = resources.getStringArray(R.array.backup_category_values)
                val entries = resources.getStringArray(R.array.backup_category_entries)
                val checkedItems : ArrayList<Boolean> = arrayListOf()
                values.forEach { _ ->
                    checkedItems.add(true)
                }

                builder.setMultiChoiceItems(
                    entries,
                    checkedItems.toBooleanArray()
                ) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }

                builder.setPositiveButton(
                    getString(R.string.ok)
                ) { _: DialogInterface?, _: Int ->
                    lifecycleScope.launch {
                        if (checkedItems.all { !it }){
                            Snackbar.make(requireView(), R.string.select_backup_categories, Snackbar.LENGTH_SHORT).show()
                            return@launch
                        }

                        val selectedItems = values.mapIndexed { idx, it -> Pair<String, Boolean>(it, checkedItems[idx]) }.filter { it.second }.map { it.first }
                        val pathResult = withContext(Dispatchers.IO){
                            settingsViewModel.backup(selectedItems)
                        }

                        if (pathResult.isFailure) {
                            val errorMessage = pathResult.exceptionOrNull()?.message ?: requireContext().getString(R.string.errored)
                            val snack = Snackbar.make(requireView(), errorMessage, Snackbar.LENGTH_LONG)
                            val snackbarView: View = snack.view
                            val snackTextView = snackbarView.findViewById<View>(com.google.android.material.R.id.snackbar_text) as TextView
                            snackTextView.maxLines = 9999999
                            snack.setAction(android.R.string.copy){
                                UiUtil.copyToClipboard(errorMessage ?: requireContext().getString(R.string.errored), requireActivity())
                            }
                            snack.show()
                        }else {
                            val s = Snackbar.make(
                                requireView(),
                                getString(R.string.backup_created_successfully),
                                Snackbar.LENGTH_LONG
                            )
                            s.setAction(R.string.Open_File) {
                                FileUtil.openFileIntent(requireActivity(), pathResult.getOrNull()!!)
                            }
                            s.show()
                        }
                    }
                }

                // handle the negative button of the alert dialog
                builder.setNegativeButton(
                    getString(R.string.cancel)
                ) { _: DialogInterface?, _: Int -> }


                val dialog = builder.create()
                dialog.show()
                true
            }
        restore!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, BackupSettingsUtil.RESTORE_MIME_TYPES)
                }
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                appRestoreResultLauncher.launch(intent)
                true
            }

        backupPath?.apply {
            summary = FileUtil.formatPath(FileUtil.getBackupPath(requireContext()))
            setOnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                backupPathResultLauncher.launch(intent)
                true
            }
        }
    }

    private var backupPathResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let {
                activity?.contentResolver?.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }

            val path = result.data!!.data.toString()
            backupPath!!.summary = FileUtil.formatPath(path)
            editor.putString("backup_path", path)
            editor.apply()
        }
    }



    private var appRestoreResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let {
                activity?.contentResolver?.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            lifecycleScope.launch {
                runCatching {
                    val (restoreData, summary) = BackupSettingsUtil.parse(requireContext(), result.data!!.data!!)
                    val parsedDataMessage = StringBuilder(summary)

                    showAppRestoreInfoDialog(
                        onMerge = {
                            lifecycleScope.launch {
                                val res = withContext(Dispatchers.IO){
                                    settingsViewModel.restoreData(restoreData, requireContext())
                                }
                                if (res) {
                                    showRestoreFinishedDialog(restoreData, parsedDataMessage.toString())
                                }else{
                                    throw Error()
                                }
                            }
                        },
                        onReset =  {
                            lifecycleScope.launch {
                                val res = withContext(Dispatchers.IO){
                                    settingsViewModel.restoreData(restoreData, requireContext(),true)
                                }
                                if (res) {
                                    showRestoreFinishedDialog(restoreData, parsedDataMessage.toString())
                                }else{
                                    throw Error()
                                }
                            }
                        }
                    )

                }.onFailure {
                    it.printStackTrace()
                    Snackbar.make(requireView(), it.message.toString(), Snackbar.LENGTH_INDEFINITE).show()
                }
            }

        }
    }



    @SuppressLint("RestrictedApi")
    private fun showAppRestoreInfoDialog(onMerge: () -> Unit, onReset: () -> Unit){
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(getString(R.string.restore))
        builder.setMessage(getString(R.string.restore_info))

        builder.setNegativeButton(getString(R.string.cancel)) { dialog : DialogInterface?, _: Int ->
            dialog?.dismiss()
        }

        builder.setPositiveButton(getString(R.string.restore)) { dialog : DialogInterface?, _: Int ->
            onMerge()
            dialog?.dismiss()
        }

        builder.setNeutralButton(getString(R.string.reset)) { dialog : DialogInterface?, _: Int ->
            onReset()
            dialog?.dismiss()
        }


        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).gravity = Gravity.START
    }

    private fun showRestoreFinishedDialog(data: RestoreAppDataItem, restoreDataMessage: String) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(getString(R.string.restore))
        builder.setMessage("${getString(R.string.restore_complete)}\n\n$restoreDataMessage")
        builder.setCancelable(false)
        builder.setPositiveButton(
            getString(R.string.ok)
        ) { _: DialogInterface?, _: Int ->
            ThemeUtil.recreateAllActivities()
        }

        val dialog = builder.create()
        dialog.show()
    }
}
