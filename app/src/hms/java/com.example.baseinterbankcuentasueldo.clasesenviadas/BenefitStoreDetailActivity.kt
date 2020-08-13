package pe.com.interbank.cuentasueldo.ui.detail

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.VectorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.huawei.hms.maps.CameraUpdateFactory
import com.huawei.hms.maps.HuaweiMap
import com.huawei.hms.maps.OnMapReadyCallback
import com.huawei.hms.maps.SupportMapFragment
import com.huawei.hms.maps.model.LatLng
import com.huawei.hms.maps.model.MarkerOptions
import kotlinx.android.synthetic.main.activity_benefit_store.*
import pe.com.interbank.cuentasueldo.R
import pe.com.interbank.cuentasueldo.common.Constants
import pe.com.interbank.cuentasueldo.common.util.CommonUtil
import pe.com.interbank.cuentasueldo.common.util.GPSUtil
import pe.com.interbank.cuentasueldo.ui.BaseActivity
import pe.com.interbank.cuentasueldo.ui.custom.CSLocationLiveData
import pe.com.interbank.cuentasueldo.ui.custom.onClickListener
import pe.com.interbank.cuentasueldo.ui.model.CategoryModel
import pe.com.interbank.cuentasueldo.ui.model.ShopModel
import pe.com.interbank.cuentasueldo.viewmodel.interactor.BenefitStoreInteractor
import pe.com.interbank.cuentasueldo.viewmodel.interactor.BenefitStoresInteractorFactory


/*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
 */
class BenefitStoreDetailActivity : BaseActivity(), OnMapReadyCallback {

    private var location: LatLng? = null
    private var storeWidth: Float = 0f
    private lateinit var locationLiveData: CSLocationLiveData // TODO HMS validar si usa el Location de GMS par reemplazarlo por el Location de HMS
    private lateinit var interactor: BenefitStoreInteractor

    override val view: Int = R.layout.activity_benefit_store

    override val screenName: String = ""
    override fun trackScreenBundle(): Bundle = Bundle()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val factory = BenefitStoresInteractorFactory(this)
        with(ViewModelProviders.of(this, factory)[BenefitStoreInteractor::class.java]) {
            interactor = this
            storeData.observe(this@BenefitStoreDetailActivity, Observer { loadStore(it) })
            errorData.observe(this@BenefitStoreDetailActivity, Observer { processError(it) })
            loadingData.observe(this@BenefitStoreDetailActivity, Observer { updateLoadingState(it) })
            val local: Int = intent?.extras?.getInt(STORE_KEY, 0) ?: 0
            load(local)
        }


        locationLiveData = CSLocationLiveData(this)
        locationLiveData.init(this)
        locationLiveData.observe(this, Observer { trackGPSProcess(it) })
    }

    override fun onStart() {
        super.onStart()
        interactor.init()
        locationLiveData.updateDistanceInteractor.init()
    }

    override fun onDestroy() {
        super.onDestroy()
        interactor.dispose()
        locationLiveData.updateDistanceInteractor.dispose()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            ENABLE_GPS_REQUEST -> locationLiveData.checkGPSSettingResponse()
            SETTINGS_REQUEST -> locationLiveData.checkGPSResolutionResponse()
            PERMISSION_REQUEST -> locationLiveData.checkGPSPermissionSetting()
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            LOCATION_CODE -> locationLiveData.checkPermissionResult(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onMapReady(hmap: HuaweiMap?) {
        updateLoadingState(true)
        hmap?.isMyLocationEnabled = true
        hmap?.uiSettings?.isMyLocationButtonEnabled = true

        //val bitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_store_marker)
        val bitmapDescriptorFactory = CommonUtil.generateBitmapDescriptorFromRes(this, R.drawable.ic_store_marker_new) // TODO HMS validar si usa el Bitmap de GMS para reemplazar esa llamada
        hmap?.moveCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))
        hmap?.setInfoWindowAdapter(MapInfoWindowAdapter(LayoutInflater.from(this).inflate(R.layout.item_map_info, ctbToolbar, false)))
        //hmap?.addMarker(MarkerOptions().icon(BitmapDescriptorFactory.fromBitmap(bitmap)).position(location!!))?.showInfoWindow()
        hmap?.addMarker(MarkerOptions().icon(bitmapDescriptorFactory).position(location!!))?.showInfoWindow()
        hmap?.setOnInfoWindowClickListener { showUpNavigation() }

        ctlAddress.onClickListener {
            val bounds = hmap?.projection?.visibleRegion?.latLngBounds
            if (bounds?.contains(location) == false) {
                CameraUpdateFactory.newLatLngZoom(location, 15f)
                    .apply { hmap.animateCamera(this) }
            }
        }
    }

    private fun loadStore(stores: List<ShopModel>) {
        if (stores.isNotEmpty()) {
            val store = stores[0]
            val category: CategoryModel = intent?.extras?.getParcelable(CATEGORY_KEY)!!
            val commerce: String = intent?.extras?.getString(COMMERCE_KEY)!!

            ctbToolbar.initMapToolbar(category, commerce) { onBackPressed() }
            location = LatLng(store.latitude?.toDouble() ?: 0.0, store.longitude?.toDouble() ?: 0.0)

            tvwAddress.text = store.address
            tvwCity.text = store.district
            if (store.distance != 0L && store.distance != null)
                if (storeWidth == 0f) {
                    tvwCity.post {
                        storeWidth = CommonUtil.calculateWidth(tvwCity.width, store.distance?.toFloat()
                                ?: 0F)
                        CommonUtil.insideEllipseSize(tvwCity, store.district.orEmpty(), store.distance?.toFloat()
                                ?: 0f, storeWidth)
                    }
                } else {
                    CommonUtil.insideEllipseSize(tvwCity, store.district.orEmpty(),
                            store.distance?.toFloat() ?: 0f, storeWidth)
                }
        }

        if (GPSUtil.checkGPSState(this) != Constants.GPS_OFF && GPSUtil.checkGPSPermission(this))
            loadMap()
        else
            locationLiveData.updateBenefitDistance(defaultCall = true, unstoppableProcess = true)
    }

    private fun trackGPSProcess(gpsState: Int) {
        when (gpsState) {
            Constants.LOCATION_STATE_LOADING_SHOW -> updateLoadingState(true)
            Constants.LOCATION_STATE_LOADING_HIDE -> updateLoadingState(false)
            Constants.LOCATION_UPDATE_COMPLETE -> loadMap()
            Constants.LOCATION_UPDATE_FAILURE -> updateLoadingState(false)
            Constants.LOCATION_PERMISSION_REQUEST -> ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_CODE)
            Constants.LOCATION_PERMISSION_SETTING -> {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", packageName, null))
                startActivityForResult(intent, PERMISSION_REQUEST)
            }
            Constants.LOCATION_SETTING_REQUEST -> startActivityForResult(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), ENABLE_GPS_REQUEST)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showUpNavigation() {
        locationLiveData.fusedLocationProviderClient.lastLocation.addOnCompleteListener {
            if (it.isSuccessful) {
                val fromDirection = it.result?.run { "$latitude,$longitude" }
                val toDirection = location?.run { "$latitude,$longitude" }

                if (checkWazeNotInstalled()) showPicker("http://maps.google.com/maps?daddr=$toDirection&saddr=$fromDirection")
                else showPicker("http://maps.google.com/maps?daddr=$toDirection&saddr=$fromDirection", "waze://?ll=${location?.latitude},${location?.longitude}&navigate=yes")
            }
        }
    }

    private fun loadMap() {
        val mapFragment: SupportMapFragment? = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment? // TODO HMS se necesita el xml para migrarlo
        mapFragment?.getMapAsync(this)
    }

    private fun showPicker(googleMapsURL: String, wazeURL: String = "") {
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_AppCompat_Light_Dialog))
                .setTitle(R.string.benefit_detail_map_dialog_title)
                .setItems(
                        if (wazeURL.isBlank()) arrayOf(getString(R.string.benefit_detail_map_dialog_google))
                        else arrayOf(getString(R.string.benefit_detail_map_dialog_google), getString(R.string.benefit_detail_map_dialog_waze)))
                { _, which ->
                    try {
                        when (which) {
                            0 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(googleMapsURL)).setPackage("com.google.android.apps.maps"))
                            else -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(wazeURL)))
                        }
                    } catch (e: ActivityNotFoundException) {
                        Log.d(TAG, e.message, e)
                    }
                }.show()
    }

    private fun checkWazeNotInstalled(): Boolean {
        try {
            packageManager.getPackageInfo("com.waze", 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "package not found, ${e.message}", e)
            return true
        }
        return false
    }

    companion object {
        private const val CATEGORY_KEY: String = "category_key"
        private const val STORE_KEY: String = "store_key"
        private const val COMMERCE_KEY: String = "commerce_key"

        fun intent(context: Context, category: CategoryModel, store: Int, commerce: String): Intent = Intent(context, BenefitStoreDetailActivity::class.java).apply {
            putExtra(CATEGORY_KEY, category)
            putExtra(STORE_KEY, store)
            putExtra(COMMERCE_KEY, commerce)
        }
    }
}