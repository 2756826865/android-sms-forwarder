package org.fossify.messages.activities

import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.BuildConfig
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityAboutBinding
import org.fossify.messages.extensions.showSmsStyled

class AboutActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityAboutBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge()
        setupTopAppBar(binding.aboutAppbar, NavigationIcon.Arrow)
        binding.aboutToolbar.title = ""
        window.statusBarColor = Color.rgb(247, 247, 247)
        window.navigationBarColor = Color.rgb(247, 247, 247)

        binding.aboutCommonQuestions.setOnClickListener {
            showText(R.string.about_common_questions, R.string.about_common_questions_text)
        }
        binding.aboutKnownIssues.setOnClickListener {
            showText(R.string.about_known_issues, R.string.about_known_issues_text)
        }
        binding.aboutLicenses.setOnClickListener {
            showText(R.string.about_licenses, R.string.about_licenses_text)
        }
        binding.aboutRepository.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_REPOSITORY)))
        }
        binding.aboutVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
    }

    private fun showText(title: Int, message: Int) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .create()
            .showSmsStyled()
    }

    companion object {
        private const val PROJECT_REPOSITORY =
            "https://github.com/2756826865/sms-forwarder-huawei"
    }
}
