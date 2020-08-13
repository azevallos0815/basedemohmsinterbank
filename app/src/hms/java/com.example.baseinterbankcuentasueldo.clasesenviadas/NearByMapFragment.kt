package pe.com.interbank.cuentasueldo.ui.group.nearby

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Point
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AccelerateInterpolator
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
/*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.tasks.OnCompleteListener
 */
import kotlinx.android.synthetic.main.fragment_near_by_map.*
import pe.com.interbank.cuentasueldo.R
import pe.com.interbank.cuentasueldo.common.Constants
import pe.com.interbank.cuentasueldo.common.GlideApp
import pe.com.interbank.cuentasueldo.common.Preferences
import pe.com.interbank.cuentasueldo.common.util.AnalyticsUtil
import pe.com.interbank.cuentasueldo.common.util.GPSUtil
import pe.com.interbank.cuentasueldo.ui.BaseActivity
import pe.com.interbank.cuentasueldo.ui.BaseFragment
import pe.com.interbank.cuentasueldo.ui.Session
import pe.com.interbank.cuentasueldo.ui.custom.isVisible
import pe.com.interbank.cuentasueldo.ui.custom.onClickListener
import pe.com.interbank.cuentasueldo.ui.detail.BenefitDetailActivity
import pe.com.interbank.cuentasueldo.ui.model.BenefitModel
import pe.com.interbank.cuentasueldo.viewmodel.interactor.NearByInteractor
import pe.com.interbank.cuentasueldo.viewmodel.interactor.NearByInteractorFactory


import com.huawei.hms.maps.OnMapReadyCallback
import com.huawei.hms.maps.model.LatLng
import com.huawei.hms.maps.model.Marker
import com.huawei.hms.maps.model.MarkerOptions
import com.huawei.hmf.tasks.OnCompleteListener
import com.huawei.hms.location.LocationServices
import com.huawei.hms.maps.CameraUpdateFactory
import com.huawei.hms.maps.HuaweiMap
import com.huawei.hms.maps.SupportMapFragment


class NearByMapFragment : BaseFragment(), OnMapReadyCallback {

    private lateinit var interactor: NearByInteractor
    private val markers: MutableList<Marker> = mutableListOf()
    private val adapter: NearByMapAdapter = NearByMapAdapter(   // TODO HMS se necesitaria validar si se usa algun objecto o model del GMS, de ser asi, lo necesitariamos para poder migrar
        favoriteListener = { code, favorite -> favorite(code, favorite) },
        detailListener = { code, pos -> detail(code, pos) })

    private var position: LatLng? = null
    private var hmap: HuaweiMap? = null
    private var locationButton: View? = null
    private var focusedPosition = 0
    var customSearch: Boolean = false

    override val viewID: Int = R.layout.fragment_near_by_map

    override val screenName: String = ""

    override fun trackScreenBundle(): Bundle = Bundle()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        adapter.radiusCorner = resources.getDimensionPixelOffset(R.dimen.home_4dp)
        adapter.requestBuilder = GlideApp.with(activity!!).load(R.drawable.ic_highlight_holder).apply(RequestOptions().transform(CenterCrop(), RoundedCorners(adapter.radiusCorner))).diskCacheStrategy(DiskCacheStrategy.ALL)

        parentFragment?.run {
            if (this is NearByFragment) {
                val factory = NearByInteractorFactory(activity!!)
                interactor = ViewModelProviders.of(this, factory)[NearByInteractor::class.java]
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val mapFragment: SupportMapFragment? = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment?
        mapFragment?.getMapAsync(this)

        locationButton = mapFragment?.view?.findViewWithTag("GoogleMapMyLocationButton")  // TODO HMS para poder modificar la funcionabilidades con este boton necesitariamos el xml

        lltSearch.alpha = 0F
        lltSearch.translationY = (resources.getDimensionPixelSize(R.dimen.near_by_60dp) + resources.getDimensionPixelSize(R.dimen.near_by_35dp)).toFloat().unaryMinus()

        lltNoData.alpha = 0F
        lltNoData.translationY = resources.getDimensionPixelSize(R.dimen.near_by_60dp).toFloat().unaryMinus()

        searchVisibility(true)
        searchLoadingVisibility(true)

        rvwBenefits.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        (rvwBenefits.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        rvwBenefits.adapter = adapter
        PagerSnapHelper().attachToRecyclerView(rvwBenefits)

        val items = arguments?.getParcelableArrayList(NearByFragment.BENEFIT_KEY) ?: mutableListOf<BenefitModel>()
        adapter.addItems(items)

        rvwBenefits.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)

                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    var newPos = (rvwBenefits.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition()
                    if (newPos == -1) newPos = 0
                    if (markers.isNotEmpty()) {
                        selectMarker(markers[newPos], newPos)
                        markers[newPos].let {
                            animateCamTo(it.position.latitude.toFloat(), it.position.longitude.toFloat())
                        }
                    }
                    focusedPosition = newPos
                }
            }
        })

        lltSearch.onClickListener {
            if (fltLoading.alpha == 0F && lltSearch.alpha == 1F) {
                searchLoadingVisibility(true)
                listVisibility(false)
                switchVisibility(false)

                focusedPosition = 0
                hmap?.clear()
                markers.clear()
                adapter.clear()
                customSearch = true

                Preferences.instance(activity!!).lastUpdate = 0
                if ((hmap?.cameraPosition?.zoom ?: 0f) < 13f)
                    hmap?.cameraPosition?.target?.let { animateCamTo(it.latitude.toFloat(), it.longitude.toFloat(), firstCall = true, isSearch = true) }
                else
                    interactor.loadFromPoint(position, hmap?.cameraPosition?.target)

                // TODO HMS ya que para interbank es muy importante los Analytics, necesitariamos su archivo AnalyticsUtils
                AnalyticsUtil.instance(activity!!).trackEvent(Constants.ADOBE_EVENT_NEAR_BY_SEARCH,
                    AnalyticsUtil.eventBundle(Constants.ADOBE_STATUS_LOGGED_IN, Constants.ADOBE_ACTION_NEAR_BY_SEARCH, Session.getInstance().user?.digitalId.orEmpty()).apply {
                        putString(Constants.APP_SEARCH_KEYWORD, "${position?.latitude};${position?.longitude}")
                    })
            }
        }

        ivwClose.onClickListener {
            noDataVisibility(false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            BaseActivity.SETTINGS_REQUEST -> camStartPosition()
            NearByFragment.TRACK_FOCUS -> {
                if (resultCode == Activity.RESULT_OK && isAdded) {
                    if (arguments == null) arguments = Bundle()
                    arguments?.putInt(NearByFragment.FOCUS_INDEX, data?.getIntExtra(NearByFragment.FOCUS_INDEX, 0) ?: 0)
                    arguments?.putBoolean(TRACK_FOCUS, true)
                }
            }
            NearByFragment.TRACK_FAVORITE -> {
                updateFavorites(data?.getIntExtra(NearByFragment.FOCUS_INDEX, 0) ?: 0,
                    data?.getBooleanExtra(NearByFragment.FAVORITE_KEY, false) ?: false)
            }
        }
    }

    override fun setUserVisibleHint(isVisibleToUser: Boolean) {
        super.setUserVisibleHint(isVisibleToUser)
        if (!isVisibleToUser) {
            if (adapter.items.isNotEmpty())
                (parentFragment as? NearByFragment)?.trackSelectedItem(focusedPosition)
            return
        }

        rvwBenefits?.translationY = 0f
        rvwBenefits?.alpha = 1f

        locationButton?.translationY = resources.getDimensionPixelSize(R.dimen.near_by_50dp).toFloat()

        arguments?.run {
            if (getBoolean(TRACK_FOCUS, false) && markers.isNotEmpty()) {
                val newPos = getInt(NearByFragment.FOCUS_INDEX, 0)
                rvwBenefits?.scrollToPosition(newPos)
                animateCamTo(markers[newPos].position.latitude.toFloat(), markers[newPos].position.longitude.toFloat(), animate = false)
                selectMarker(markers[newPos], newPos)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onMapReady(map: HuaweiMap?) {
        this.hmap = map
        hmap?.isMyLocationEnabled = true
        hmap?.uiSettings?.isMyLocationButtonEnabled = true
        hmap?.setOnMarkerClickListener {
            it.tag?.let { tag ->
                val markerPos = tag.toString().toInt()
                this.hmap?.let {
                    val location = markers[markerPos].position
                    animateCamTo(location.latitude.toFloat(), location.longitude.toFloat())
                    rvwBenefits.scrollToPosition(markerPos)
                }

                selectMarker(it, markerPos)
                if (adapter.items.isNotEmpty()) {
                    listVisibility(true)
                    switchVisibility(true)
                }

                focusedPosition = markerPos
            }
            return@setOnMarkerClickListener true
        }

        hmap?.setOnCameraMoveStartedListener {
            if (it == HuaweiMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                searchVisibility(true)
                listVisibility(false)
                switchVisibility(false)
                noDataVisibility(false)
            }
        }

        hmap?.setOnMapClickListener {
            if (rvwBenefits.alpha == 1f) {
                listVisibility(false)
                switchVisibility(false)
            } else if (adapter.items.isNotEmpty()) {
                listVisibility(true)
                switchVisibility(true)
            }
        }

        hmap?.setOnMapLoadedCallback { camStartPosition() }
    }

    fun calculate() {
        if (viwHelper == null) return

        val center = Location("center")
        hmap?.cameraPosition?.target?.let {
            center.latitude = it.latitude
            center.longitude = it.longitude
        }

        val point = Point(viwHelper.pivotX.toInt(), viwHelper.pivotY.toInt())
        val corner = Location("corner")
        hmap?.projection?.fromScreenLocation(point)?.let {
            corner.latitude = it.latitude
            corner.longitude = it.longitude
        }

        interactor.benefit(center.distanceTo(corner).toInt())
    }

    fun updateItems(result: List<BenefitModel>) {
        adapter.addItems(result)
    }

    fun updateMap(result: List<MarkerOptions>) {
        if (markers.isNotEmpty()) return
        if (result.isNotEmpty()) {
            hmap?.let {
                if (adapter.items.isNotEmpty()) {
                    listVisibility(true)
                    noDataVisibility(false)
                    searchVisibility(false)
                    searchLoadingVisibility(false)
                    switchVisibility(true)

                    markers.addAll(result.mapIndexed { index, markerOption -> it.addMarker(markerOption).apply { tag = index } })
                    with(it.cameraPosition.target) { animateCamTo(latitude.toFloat(), longitude.toFloat()) }

                    if (markers.isNotEmpty()) selectMarker(markers[focusedPosition], focusedPosition)
                } else {
                    noDataVisibility(true)
                    searchVisibility(false)
                    searchLoadingVisibility(false)
                }
            }
        } else {
            noDataVisibility(true)
            searchVisibility(false)
            searchLoadingVisibility(false)
        }
    }

    private fun updateFavorites(code: Int, favorite: Boolean) {
        adapter.items.filter { it.code == code }.forEach { it.favorite = favorite }
        adapter.notifyDataSetChanged()
    }

    private fun favorite(benefitCode: Int, favorite: Boolean) {
        (parentFragment as? NearByFragment)?.updateFavorite(benefitCode, favorite)
        Session.getInstance().user?.run {
            interactor.updateFavorite(benefitCode, favorite, docNumber.orEmpty(), docType.orEmpty())
        }
        updateFavorites(benefitCode, favorite)
    }

    private fun detail(benefitCode: Int, position: Int) {
        val bounds = hmap?.projection?.visibleRegion?.latLngBounds
        if (markers.isEmpty() || bounds?.contains(markers[position].position) == true) {
            parentFragment?.startActivityForResult(BenefitDetailActivity.intent(activity!!, benefitCode), BaseActivity.COMMON_REQUEST)
        } else if (markers.isNotEmpty()) {
            with(markers[position].position) { animateCamTo(latitude.toFloat(), longitude.toFloat()) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun camStartPosition() {
        activity?.run {
            LocationServices
                .getFusedLocationProviderClient(this)
                .lastLocation
                .addOnCompleteListener(activity!!, OnCompleteListener { task ->
                    if (!isAdded) return@OnCompleteListener
                    if (task.isSuccessful && task.result != null) {
                        position = task.result?.let { LatLng(it.latitude, it.longitude) }
                        animateCamTo(position?.latitude?.toFloat() ?: 0F, position?.longitude?.toFloat() ?: 0F, true)
                    } else
                        GPSUtil.requestDeviceLocation(activity as BaseActivity) { camStartPosition() } // TODO HMS se necesita el archivo GPSUTIL para poder integrar el Location Kit
                })
        }
    }

    private fun animateCamTo(lat: Float, lon: Float, firstCall: Boolean = false, isSearch: Boolean = false, animate: Boolean = true) {
        val zoom = when {
            isSearch -> 13F
            hmap?.cameraPosition?.zoom ?: 0F < 4F -> 15F
            else -> hmap?.cameraPosition?.zoom ?: 15F
        }
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(LatLng(lat.toDouble(), lon.toDouble()), zoom)
        if (firstCall)
            if (animate) {
                hmap?.animateCamera(cameraUpdate, object : HuaweiMap.CancelableCallback {
                    override fun onFinish() {
                        Log.e("ASKLL", "TEST")
                        if (isSearch) interactor.loadFromPoint(position, hmap?.cameraPosition?.target)
                        else calculate()
                    }

                    override fun onCancel() {
                        // Empty
                    }
                })
            } else {
                hmap?.moveCamera(cameraUpdate)
                calculate()
            }
        else
            hmap?.animateCamera(cameraUpdate)
    }

    private fun listVisibility(state: Boolean) {
        if (rvwBenefits.alpha == (if (state) 1F else 0F)) return

        val movement = resources.getDimensionPixelSize(R.dimen.near_by_84dp).toFloat()
        val translateAnim =
            (if (state) ObjectAnimator.ofFloat(rvwBenefits, "translationY", movement, 0f)
            else ObjectAnimator.ofFloat(rvwBenefits, "translationY", 0f, movement)).apply {
                interpolator = AccelerateInterpolator()
                duration = 400
            }

        val alphaAnim =
            (if (state) ObjectAnimator.ofFloat(rvwBenefits, "alpha", 0f, 1f)
            else ObjectAnimator.ofFloat(rvwBenefits, "alpha", 1f, 0f)).apply {
                interpolator = AccelerateInterpolator()
                duration = 300
                if (state) startDelay = 100
            }

        AnimatorSet().apply {
            playTogether(translateAnim, alphaAnim)
            start()
        }
    }

    private fun noDataVisibility(state: Boolean) {
        if (lltNoData.alpha == (if (state) 1F else 0F)) return

        val movement = resources.getDimensionPixelSize(R.dimen.near_by_60dp).toFloat()
        val translateAnim =
            (if (state) ObjectAnimator.ofFloat(lltNoData, "translationY", movement, 0f)
            else ObjectAnimator.ofFloat(lltNoData, "translationY", 0f, movement)).apply {
                interpolator = AccelerateInterpolator()
                duration = 300
                if (state) startDelay = 100
            }

        val alphaAnim =
            (if (state) ObjectAnimator.ofFloat(lltNoData, "alpha", 0f, 1f)
            else ObjectAnimator.ofFloat(lltNoData, "alpha", 1f, 0f)).apply {
                interpolator = AccelerateInterpolator()
                duration = 300
                if (state) startDelay = 100
            }

        AnimatorSet().apply {
            playTogether(translateAnim, alphaAnim)
            start()
        }
    }

    private fun searchVisibility(state: Boolean) {
        if (lltSearch.alpha == (if (state) 1F else 0F)) return

        val movement = (resources.getDimensionPixelSize(R.dimen.near_by_60dp) + resources.getDimensionPixelSize(R.dimen.near_by_35dp)).toFloat().unaryMinus()
        val translateAnim =
            (if (state) ObjectAnimator.ofFloat(lltSearch, "translationY", movement, 0f)
            else ObjectAnimator.ofFloat(lltSearch, "translationY", 0f, movement)).apply {
                interpolator = AccelerateInterpolator()
                duration = 400
                if (state) startDelay = 150
            }

        val alphaAnim =
            (if (state) ObjectAnimator.ofFloat(lltSearch, "alpha", 0f, 1f)
            else ObjectAnimator.ofFloat(lltSearch, "alpha", 1f, 0f)).apply {
                interpolator = AccelerateInterpolator()
                duration = 300
                if (state) startDelay = 150
            }

        AnimatorSet().apply {
            playTogether(translateAnim, alphaAnim)
            start()
        }
    }

    private fun searchLoadingVisibility(state: Boolean) {
        viwBlocker.isVisible = state
        val alphaAnim =
            (if (state) ObjectAnimator.ofFloat(fltLoading, "alpha", 0f, 1f)
            else ObjectAnimator.ofFloat(fltLoading, "alpha", 1f, 0f)).apply {
                interpolator = AccelerateInterpolator()
                duration = 150
                if (state) startDelay = 50
            }

        alphaAnim.start()
    }

    private fun switchVisibility(state: Boolean) {
        val fragment = activity?.supportFragmentManager?.findFragmentByTag(NearByFragment.TAG)
        if (fragment is NearByFragment)
            fragment.switchVisibility(state)
        moveLocationButton(state)
    }

    private fun moveLocationButton(state: Boolean) {
        val movement = resources.getDimensionPixelSize(R.dimen.near_by_50dp).toFloat()
        if (locationButton == null || (if (state) locationButton?.translationY ?: 0F > 0F else locationButton?.translationY == 0F)) return

        (if (state) ObjectAnimator.ofFloat(locationButton!!, "translationY", 0f, movement)
        else ObjectAnimator.ofFloat(locationButton!!, "translationY", movement, 0f)).apply {
            interpolator = AccelerateInterpolator()
            duration = 200
            start()
        }
    }

    private fun selectMarker(marker: Marker, currentPos: Int) {
        interactor.markerData?.let {
            markers[focusedPosition].setIcon(it[adapter.items[focusedPosition].category?.code.toString()]?.get(0))
            markers[focusedPosition].zIndex = 0f

            marker.setIcon(it[adapter.items[currentPos].category?.code.toString()]?.get(1))
            marker.zIndex = 99f

            focusedPosition = currentPos
        }
    }

    companion object {
        val TAG: String = NearByMapFragment::class.java.canonicalName.orEmpty()
        const val TRACK_FOCUS: String = "should_track_focus"

        fun instance(): NearByMapFragment = NearByMapFragment()
    }
}