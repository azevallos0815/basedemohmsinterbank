package com.example.baseinterbankcuentasueldo.clasesenviadas

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.util.Log
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.adobe.mobile.Config
import com.facebook.appevents.AppEventsLogger
import com.facebook.applinks.AppLinkData
import com.google.firebase.iid.FirebaseInstanceId
import kotlinx.android.synthetic.main.activity_splash.*
import pe.com.interbank.cuentasueldo.R
import pe.com.interbank.cuentasueldo.common.Constants
import pe.com.interbank.cuentasueldo.common.Preferences
import pe.com.interbank.cuentasueldo.ui.BaseActivity
import pe.com.interbank.cuentasueldo.ui.Session
import pe.com.interbank.cuentasueldo.ui.custom.isVisible
import pe.com.interbank.cuentasueldo.ui.home.HomeActivity
import pe.com.interbank.cuentasueldo.ui.login.LoginActivity
import pe.com.interbank.cuentasueldo.ui.model.ErrorModel
import pe.com.interbank.cuentasueldo.ui.model.LoginModel
import pe.com.interbank.cuentasueldo.ui.model.VersionModel
import pe.com.interbank.cuentasueldo.viewmodel.exception.UpgradeAppException
import pe.com.interbank.cuentasueldo.viewmodel.interactor.AuthInteractor
import pe.com.interbank.cuentasueldo.viewmodel.interactor.AuthInteractorFactory

class SplashActivity : BaseActivity() {

    private lateinit var authInteractor: AuthInteractor

    override val view: Int = R.layout.activity_splash

    override val screenName: String = ""
    override fun trackScreenBundle(): Bundle = Bundle()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Preferences.instance(this).showFeedbackOnce = true
        FirebaseInstanceId.getInstance().instanceId.addOnSuccessListener(this) { Config.setPushIdentifier(it.token) }

        val authInteractorFactory = AuthInteractorFactory(this)
        with(ViewModelProviders.of(this, authInteractorFactory)[AuthInteractor::class.java]) {
            authInteractor = this
            loginData.observe(this@SplashActivity, Observer { logIn(it) })
            loginInfoData.observe(this@SplashActivity, Observer { checkLoginInfo() })
            versionData.observe(this@SplashActivity, Observer { validateVersion(it) })
            errorData.observe(this@SplashActivity, Observer { cleanAutoLogin() })
            loadingData.observe(this@SplashActivity, Observer { updateLoadingState(it!!) })
            checkVersion()
        }
    }

    override fun onStart() {
        super.onStart()
        authInteractor.init()
    }

    override fun onDestroy() {
        super.onDestroy()
        authInteractor.dispose()
    }

    private fun validateVersion(version: VersionModel?) {
        if (version == null || version.updateStatus.isBlank()) {
            startLogin()
            return
        }

        when (version.updateStatus.toUpperCase()) {
            Constants.VERSION_STATUS_OPTIONAL, Constants.VERSION_STATUS_REQUIRED -> {
                val isOptional = version.updateStatus.toUpperCase() == Constants.VERSION_STATUS_OPTIONAL
                processError(ErrorModel(
                        errorMessage = version.updateMessage,
                        errorIdTitle = if (isOptional) R.string.upgrade_optional_app else R.string.upgrade_required_app,
                        errorIdIcon = if (isOptional) R.drawable.ic_dialog_upgrade_optional_new else R.drawable.ic_dialog_upgrade_new,
                        positiveButton = R.string.upgrade_app_button,
                        errorType = Constants.ERROR_TYPE_RESPONSE,
                        exception = UpgradeAppException(),
                        customizedAction = { update() },
                        optionalCustomizedAction = { startLogin() },
                        isAlert = isOptional,
                        isCancelable = isOptional))
            }
            else -> if (Preferences.instance(this).sessionActive) {
                authInteractor.login(this, Preferences.instance(this).document, Preferences.instance(this).documentType.toInt() + 1)
            } else
                initializeNavigation()
        }
    }

    private fun startLogin() {
        if (Preferences.instance(this).sessionActive) {
            with(Preferences.instance(this)) {
                authInteractor.login(this@SplashActivity, document, Integer.parseInt(documentType) + 1)
            }
        } else
            initializeNavigation()
    }

    private fun logIn(data: LoginModel) {
        Preferences.instance(this).run {
            sessionActive = true
            tokenID = Session.getInstance().tokenID ?: ""
            user = data
        }
        Session.getInstance().user = data
        authInteractor.validateStatus()
    }

    private fun checkLoginInfo() {
        Log.d(TAG, "Post Login use case completed")
        initializeNavigation(true)
    }

    private fun cleanAutoLogin() {
        Preferences.instance(this).sessionActive = false
        initializeNavigation()
    }

    private fun initializeNavigation(autoLoginDone: Boolean = false) {
        val anim = AnimationUtils.loadAnimation(this@SplashActivity, R.anim.scale_anim)
        anim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation) {
                //vwLoading.isVisible = false
                //ivwSplash.alpha = 0.6f
            }

            override fun onAnimationEnd(animation: Animation) {
                /*ivwSplash.scaleX = 50f
                ivwSplash.scaleY = 50f*/
                //ivwSplash.alpha = 0f
                startNavigation(autoLoginDone)
            }

            override fun onAnimationRepeat(animation: Animation) {
                // Empty
            }
        })

        Handler().postDelayed({ ivwSplash.startAnimation(anim) }, 900)
    }

    private fun startNavigation(autoLoginDone: Boolean) {

        AppLinkData.fetchDeferredAppLinkData(this) {
            if (it != null) {
                val logger = AppEventsLogger.newLogger(this)
                if (Preferences.instance(this).firstLaunch)
                    logger.logEvent(Constants.FB_INSTALL_EVENT, 1.0)
                logger.logEvent(Constants.FB_OPEN_EVENT, 1.0)
            }
        }

        val intent = if (autoLoginDone) HomeActivity.intent(this, this.intent.extras) else LoginActivity.intent(this, this.intent.extras)
        startActivity(intent)
        finish()
    }

    private fun update() {
        val isRequired = authInteractor.versionData.value?.updateStatus.equals(Constants.VERSION_STATUS_REQUIRED)
        if (!isRequired) startLogin()

        val appPackageName = packageName
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackageName")).setPackage("com.android.vending"))
        } catch (ex: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?code=$appPackageName")))
        }
    }

    companion object {
        val TAG: String = SplashActivity::class.java.canonicalName.orEmpty()
    }
}
