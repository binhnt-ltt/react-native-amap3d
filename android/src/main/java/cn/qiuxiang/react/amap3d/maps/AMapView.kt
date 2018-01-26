package cn.qiuxiang.react.amap3d.maps

import android.content.Context
import android.content.Context.LOCATION_SERVICE
import android.content.Context.SENSOR_SERVICE
import android.hardware.*
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.*
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
import android.R.attr.orientation
import android.hardware.SensorManager.getRotationMatrix
import android.hardware.Sensor.TYPE_MAGNETIC_FIELD
import android.hardware.Sensor.TYPE_ACCELEROMETER
import android.hardware.Sensor.TYPE_GRAVITY
import android.R.attr.orientation
import android.hardware.SensorManager.getRotationMatrix
import android.hardware.Sensor.TYPE_MAGNETIC_FIELD
import android.hardware.Sensor.TYPE_ACCELEROMETER

class AMapView(context: Context) : TextureMapView(context) {
    private val eventEmitter: RCTEventEmitter = (context as ThemedReactContext).getJSModule(RCTEventEmitter::class.java)
    private val markers = HashMap<String, AMapMarker>()
    private val lines = HashMap<String, AMapPolyline>()
    private val locationStyle by lazy {
        val locationStyle = MyLocationStyle()
        locationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
        locationStyle
    }

    private var _isMapRotate: Boolean = false

    private var locationManager : LocationManager? = null
    private var mSensorManager : SensorManager? = null
    private var mAccelerometer: Sensor? = null
    private var mMagnetometer: Sensor? = null
    private var mAzimuth = 0 // degree
    private var customUserPositionMarker: AMapMarker? = null

    //define the listener
    private val locationListener: LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val event = Arguments.createMap()
            event.putDouble("latitude", location.latitude)
            event.putDouble("longitude", location.longitude)
            event.putDouble("accuracy", location.accuracy.toDouble())
            event.putDouble("altitude", location.altitude)
            event.putDouble("speed", location.speed.toDouble())
            event.putInt("timestamp", location.time.toInt())
            emit(id, "onLocation", event)
        }
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val mSensorEventListener: SensorEventListener = object : SensorEventListener {
        var gData = FloatArray(3) // accelerometer
        var mData = FloatArray(3) // magnetometer
        var rMat = FloatArray(9)
        var iMat = FloatArray(9)
        var orientation = FloatArray(3)

        override fun onSensorChanged(event: SensorEvent) {
            val data: FloatArray
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> gData = event.values.clone()
                Sensor.TYPE_MAGNETIC_FIELD -> mData = event.values.clone()
                else -> return
            }

            if (SensorManager.getRotationMatrix(rMat, iMat, gData, mData)) {
                mAzimuth = (Math.toDegrees(SensorManager.getOrientation(rMat, orientation)[0].toDouble()) + 360).toInt() % 360
                if (_isMapRotate) {
                    customUserPositionMarker?.marker?.setRotateAngle(0f)
                } else {
                    customUserPositionMarker?.marker?.setRotateAngle(-mAzimuth.toFloat())
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    init {
        super.onCreate(null)

        locationManager = context.getSystemService(LOCATION_SERVICE) as LocationManager?

        try {
            // Request location updates
            locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, locationListener);
        } catch(ex: SecurityException) {
            Log.d("myTag", "Security Exception, no location available");
        }

        mSensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager?

        mAccelerometer = mSensorManager?.getDefaultSensor( Sensor.TYPE_ACCELEROMETER );
        mSensorManager?.registerListener( mSensorEventListener, mAccelerometer, SensorManager.SENSOR_DELAY_GAME );

        mMagnetometer = this.mSensorManager?.getDefaultSensor( Sensor.TYPE_MAGNETIC_FIELD );
        mSensorManager?.registerListener( mSensorEventListener, mMagnetometer, SensorManager.SENSOR_DELAY_GAME );

        map.setOnMapClickListener { latLng ->
            for (marker in markers.values) {
                marker.active = false
            }

            val event = Arguments.createMap()
            event.putDouble("latitude", latLng.latitude)
            event.putDouble("longitude", latLng.longitude)
            emit(id, "onPress", event)

//            emitMapRotate("onChangeMapRotate", false)
        }

//        map.setOnMapLongClickListener { latLng ->
//            val event =  Arguments.createMap()
//            event.putDouble("latitude", latLng.latitude)
//            event.putDouble("longitude", latLng.longitude)
//            emit(id, "onLongPress", event)
//
////            emitMapRotate("onChangeMapRotate", false)
//        }

//        map.setOnMyLocationChangeListener { location ->
//            val event = Arguments.createMap()
//            event.putDouble("latitude", location.latitude)
//            event.putDouble("longitude", location.longitude)
//            event.putDouble("accuracy", location.accuracy.toDouble())
//            event.putDouble("altitude", location.altitude)
//            event.putDouble("speed", location.speed.toDouble())
//            event.putInt("timestamp", location.time.toInt())
//            emit(id, "onLocation", event)
//        }

        map.setOnMarkerClickListener { marker ->
            emit(markers[marker.id]?.id, "onPress")
            if (_isMapRotate) {
                _isMapRotate = false
                emitMapRotate("onChangeMapRotate", false)
            }
            false
        }

        map.setOnMarkerDragListener(object : AMap.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) {
                emit(markers[marker.id]?.id, "onDragStart")
            }

            override fun onMarkerDrag(marker: Marker) {
                emit(markers[marker.id]?.id, "onDrag")
            }

            override fun onMarkerDragEnd(marker: Marker) {
                val position = marker.position
                val data = Arguments.createMap()
                data.putDouble("latitude", position.latitude)
                data.putDouble("longitude", position.longitude)
                emit(markers[marker.id]?.id, "onDragEnd", data)
            }
        })

        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChangeFinish(position: CameraPosition?) {
                emitCameraChangeEvent("onStatusChangeComplete", position)
            }

            override fun onCameraChange(position: CameraPosition?) {
                emitCameraChangeEvent("onStatusChange", position)
            }
        })

        map.setOnInfoWindowClickListener { marker ->
            emit(markers[marker.id]?.id, "onInfoWindowPress")
        }

        map.setOnPolylineClickListener { polyline ->
            emit(lines[polyline.id]?.id, "onPress")
        }

        map.setOnMultiPointClickListener { item ->
            val slice = item.customerId.split("_")
            val data = Arguments.createMap()
            data.putInt("index", slice[1].toInt())
            emit(slice[0].toInt(), "onItemPress", data)
            false
        }

        map.setOnMapTouchListener {
            if (_isMapRotate) {
                _isMapRotate = false
                emitMapRotate("onChangeMapRotate", false)
            }
        }

        map.setInfoWindowAdapter(AMapInfoWindowAdapter(context, markers))
    }

    fun emitMapRotate(event: String, isMapRotate: Boolean) {
        val locationStyle1 by lazy {
            val locationStyle1 = map.myLocationStyle
            if (isMapRotate) {
                locationStyle1.myLocationType(MyLocationStyle.LOCATION_TYPE_MAP_ROTATE)
            } else {
                locationStyle1.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
            }
            locationStyle1
        }

        map.myLocationStyle = locationStyle1

        val data = Arguments.createMap()
        data.putBoolean("isMapRotate", isMapRotate)
        emit(id, event, data)
    }

    fun emitCameraChangeEvent(event: String, position: CameraPosition?) {
        position?.let {
            val data = Arguments.createMap()
            data.putDouble("zoomLevel", it.zoom.toDouble())
            data.putDouble("tilt", it.tilt.toDouble())
            data.putDouble("rotation", it.bearing.toDouble())
            data.putDouble("latitude", it.target.latitude)
            data.putDouble("longitude", it.target.longitude)
            if (event == "onStatusChangeComplete") {
                val southwest = map.projection.visibleRegion.latLngBounds.southwest
                val northeast = map.projection.visibleRegion.latLngBounds.northeast
                data.putDouble("latitudeDelta", Math.abs(southwest.latitude - northeast.latitude))
                data.putDouble("longitudeDelta", Math.abs(southwest.longitude - northeast.longitude))
            }
            emit(id, event, data)
        }
    }

    fun emit(id: Int?, name: String, data: WritableMap = Arguments.createMap()) {
        id?.let { eventEmitter.receiveEvent(it, name, data) }
    }

    fun add(child: View) {
        if (child is AMapOverlay) {
            child.add(map)
            if (child is AMapMarker) {
                if (child.isCustomUserPosition) {
                    customUserPositionMarker = child
                }
                markers.put(child.marker?.id!!, child)
            }
            if (child is AMapPolyline) {
                lines.put(child.polyline?.id!!, child)
            }
        }
    }

    fun remove(child: View) {
        if (child is AMapOverlay) {
            child.remove()
            if (child is AMapMarker) {
                markers.remove(child.marker?.id)
            }
            if (child is AMapPolyline) {
                lines.remove(child.polyline?.id)
            }
        }
    }

    private val animateCallback = object : AMap.CancelableCallback {
        override fun onCancel() {
            emit(id, "onAnimateCancel")
        }

        override fun onFinish() {
            emit(id, "onAnimateFinish")
        }
    }

    fun animateTo(args: ReadableArray?) {
        val currentCameraPosition = map.cameraPosition
        val target = args?.getMap(0)!!
        val duration = args.getInt(1)

        var coordinate = currentCameraPosition.target
        var zoomLevel = currentCameraPosition.zoom
        var tilt = currentCameraPosition.tilt
        var rotation = currentCameraPosition.bearing

        if (target.hasKey("coordinate")) {
            val json = target.getMap("coordinate")
            coordinate = LatLng(json.getDouble("latitude"), json.getDouble("longitude"))
        }

        if (target.hasKey("zoomLevel")) {
            zoomLevel = target.getDouble("zoomLevel").toFloat()
        }

        if (target.hasKey("tilt")) {
            tilt = target.getDouble("tilt").toFloat()
        }

        if (target.hasKey("rotation")) {
            rotation = target.getDouble("rotation").toFloat()
        }

        val cameraUpdate = CameraUpdateFactory.newCameraPosition(
                CameraPosition(coordinate, zoomLevel, tilt, rotation))
        map.animateCamera(cameraUpdate, duration.toLong(), animateCallback)
    }

    fun setMapRotate(args: ReadableArray?) {
        val isMapRotate = args?.getBoolean(0)!!
        _isMapRotate = isMapRotate

        val locationStyle1 by lazy {
            val locationStyle1 = map.myLocationStyle
            if (isMapRotate) {
                locationStyle1.myLocationType(MyLocationStyle.LOCATION_TYPE_MAP_ROTATE)
            } else {
                locationStyle1.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
            }
            locationStyle1
        }

        map.myLocationStyle = locationStyle1
    }

    fun setRegion(region: ReadableMap) {
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(latLngBoundsFromReadableMap(region), 0))
    }

    fun setLimitRegion(region: ReadableMap) {
        map.setMapStatusLimits(latLngBoundsFromReadableMap(region))
    }

    fun setLocationEnabled(enabled: Boolean) {
        map.isMyLocationEnabled = enabled
        map.myLocationStyle = locationStyle
    }

    fun setLocationInterval(interval: Long) {
        locationStyle.interval(interval)
    }

    private fun latLngBoundsFromReadableMap(region: ReadableMap): LatLngBounds {
        val latitude = region.getDouble("latitude")
        val longitude = region.getDouble("longitude")
        val latitudeDelta = region.getDouble("latitudeDelta")
        val longitudeDelta = region.getDouble("longitudeDelta")
        return LatLngBounds(
                LatLng(latitude - latitudeDelta / 2, longitude - longitudeDelta / 2),
                LatLng(latitude + latitudeDelta / 2, longitude + longitudeDelta / 2)
        )
    }

    fun setLocationStyle(style: ReadableMap) {
        if (style.hasKey("fillColor")) {
            locationStyle.radiusFillColor(style.getInt("fillColor"))
        }

        if (style.hasKey("stockColor")) {
            locationStyle.strokeColor(style.getInt("stockColor"))
        }

        if (style.hasKey("stockWidth")) {
            locationStyle.strokeWidth(style.getDouble("stockWidth").toFloat())
        }

        if (style.hasKey("image")) {
            val drawable = context.resources.getIdentifier(
                    style.getString("image"), "drawable", context.packageName)
            locationStyle.myLocationIcon(BitmapDescriptorFactory.fromResource(drawable))
        }

        if (style.hasKey("isHiddenUserLocation")) {
            val isHiddenUserLocation = style.getBoolean("isHiddenUserLocation")
            if (isHiddenUserLocation) {
                locationStyle.radiusFillColor(0)
                locationStyle.strokeColor(0)
                locationStyle.strokeWidth(0f)
                val drawable = context.resources.getIdentifier(
                        "blank", "drawable", context.packageName)
                locationStyle.myLocationIcon(BitmapDescriptorFactory.fromResource(drawable))
            }
        }
    }
}
