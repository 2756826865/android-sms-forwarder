package org.fossify.messages.activities

import android.graphics.Color
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

/** App-local SMS blacklist that works even when this app is not the system dialer. */
class ManageBlacklistActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityManageWhitelistBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.whitelistList))
        setupTopAppBar(binding.whitelistAppbar, NavigationIcon.Arrow)
        binding.whitelistToolbar.title = ""
        binding.whitelistTitleText.setText(R.string.blacklist_numbers)
        applyMiuiPageChrome()
        binding.whitelistFab.setOnClickListener { showAddDialog() }
        binding.whitelistList.setOnItemLongClickListener { _, _, position, _ ->
            val number = currentNumbers().getOrNull(position)
                ?: return@setOnItemLongClickListener true
            AlertDialog.Builder(this)
                .setMessage(number)
                .setPositiveButton(org.fossify.commons.R.string.delete) { _, _ ->
                    config.removeBlacklistedNumber(number)
                    refreshList()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .showSmsStyled()
            true
        }
        refreshList()
    }

    private fun currentNumbers() = config.blacklistedNumbers.sorted()

    private fun refreshList() {
        val numbers = currentNumbers()
        binding.whitelistEmpty.apply {
            setText(R.string.blacklist_empty)
            visibility = if (numbers.isEmpty()) View.VISIBLE else View.GONE
        }
        binding.whitelistList.visibility = if (numbers.isEmpty()) View.GONE else View.VISIBLE
        binding.whitelistList.adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, numbers)
    }

    private fun showAddDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.blacklist_number_hint)
            inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(Color.rgb(17, 17, 17))
            setHintTextColor(Color.rgb(120, 120, 120))
            setPadding(48, 12, 48, 12)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.blacklist_add)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val number = input.text.toString().trim().replace(Regex("[\\s()-]"), "")
                if (number.matches(Regex("\\+?[0-9]{3,20}"))) {
                    config.addBlacklistedNumber(number)
                    toast(R.string.blacklist_saved)
                    refreshList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }
}
