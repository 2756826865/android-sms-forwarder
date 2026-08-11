package org.fossify.messages.activities

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityManageWhitelistBinding
import org.fossify.messages.extensions.applyMiuiPageChrome
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.showSmsStyled

class ManageWhitelistActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityManageWhitelistBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.whitelistList))
        setupTopAppBar(binding.whitelistAppbar, NavigationIcon.Arrow)
        binding.whitelistToolbar.title = ""
        applyMiuiPageChrome()
        binding.whitelistFab.setOnClickListener { showAddDialog() }
        binding.whitelistList.setOnItemLongClickListener { _, _, position, _ ->
            val number = currentNumbers().getOrNull(position) ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setMessage(number)
                .setPositiveButton(org.fossify.commons.R.string.delete) { _, _ ->
                    config.removeWhitelistedNumber(number)
                    refreshList()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .showSmsStyled()
            true
        }
        refreshList()
    }

    private fun currentNumbers() = config.whitelistedNumbers.sorted()

    private fun refreshList() {
        val numbers = currentNumbers()
        binding.whitelistEmpty.visibility = if (numbers.isEmpty()) View.VISIBLE else View.GONE
        binding.whitelistList.visibility = if (numbers.isEmpty()) View.GONE else View.VISIBLE
        binding.whitelistList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, numbers)
    }

    private fun showAddDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.whitelist_number_hint)
            inputType = InputType.TYPE_CLASS_PHONE
            setPadding(48, 12, 48, 12)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.whitelist_add)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val number = input.text.toString().trim().replace(Regex("[\\s()-]"), "")
                if (number.matches(Regex("\\+?[0-9]{3,20}"))) {
                    config.addWhitelistedNumber(number)
                    toast(R.string.whitelist_saved)
                    refreshList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }
}
