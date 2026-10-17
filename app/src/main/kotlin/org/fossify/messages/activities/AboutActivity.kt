package org.fossify.messages.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.BuildConfig
import org.fossify.messages.updates.ManualUpdateController
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityAboutBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.showSmsStyled

class AboutActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityAboutBinding::inflate)
    private lateinit var updateController: ManualUpdateController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padTopSystem = listOf(binding.aboutAppbar))
        setupTopAppBar(binding.aboutAppbar, NavigationIcon.Arrow)
        binding.aboutToolbar.title = ""
        applyMiuiTopAppBarChrome(binding.aboutAppbar, binding.aboutToolbar)

        updateController = ManualUpdateController(this, binding.aboutCheckUpdate)
        binding.aboutCheckUpdate.setOnClickListener { updateController.check() }

        binding.aboutUserGuide.setOnClickListener {
            startActivity(Intent(this, UserGuideActivity::class.java))
        }
        binding.aboutQqGroup.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("QQ 交流群", "569321348"))
            android.widget.Toast.makeText(this, "群号已复制：569321348", android.widget.Toast.LENGTH_SHORT).show()
            val groupIntent = Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP_INVITE))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            try {
                startActivity(Intent(groupIntent).setPackage("com.tencent.mobileqq"))
            } catch (_: android.content.ActivityNotFoundException) {
                try {
                    startActivity(groupIntent)
                } catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(this, "无法打开加群链接，请使用已复制的群号加群", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        binding.aboutSpecialStatement.setOnClickListener {
            showText(R.string.about_special_statement, R.string.first_use_notice_message)
        }
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
        binding.aboutCoolapk.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(COOLAPK_PROFILE)))
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
        private const val QQ_GROUP_INVITE =
            "https://qun.qq.com/universal-share/share?ac=1&authKey=oA1lovv3Le%2F3NODAGwDlNmfFEkjenmNPlM1jr92An%2BGuY4aliEni9xu4HTh0avKR&busi_data=eyJncm91cENvZGUiOiI1NjkzMjEzNDgiLCJ0b2tlbiI6IkU5MUJNc29aYlJYTDlqNHlHSFc1aExOaHladUQyZVV6Sm95ZjlwdHhRRzM0QXZFeXJYQ1laNUdqdkRiSTBLL0IiLCJ1aW4iOiIyNzU2ODI2ODY1In0%3D&data=GqZa5ouDqCxEUJJDapcFruBqASgcseMKcmpMhZM1IVgWd6CDGE9_FWhBfTMpBANO_rbAPJLHbisWHkB60HajIA&svctype=4&tempid=h5_group_info"
        private const val PROJECT_REPOSITORY =
            "https://github.com/2756826865/android-sms-forwarder"
        private const val COOLAPK_PROFILE = "https://www.coolapk.com/u/3270136"
    }
}
