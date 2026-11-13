package com.coloros16.liquidglass.hook

/** Pure scope rules: do not touch PIN/password, fingerprint, notifications or AOD. */
object KeyguardGlassPolicy {
    const val NONE=0;const val TEXT=1;const val CARD=2;const val SHORTCUT=3
    fun isClockGlyphRoot(name:String)=name in setOf("com.oplus.keyguard.clock.base.ui.view.ClockViewRoot",
        "com.oplus.keyguard.clock.base.ui.view.SingleClockView")
    @JvmOverloads
    fun role(names:List<String>,id:String,textView:Boolean,background:Boolean,clickable:Boolean,aspect:Float,
        trustedRole:Int=NONE,customGlyph:Boolean=false):Int {
        val path=names.joinToString("/").lowercase();val key=id.lowercase()
        if(path.contains(".aod.") || path.contains("statusbarclock") || path.contains("keystatusbar") || path.contains("keyguardstatusbar") || path.contains("security.") || path.contains("bouncer") || path.contains("password") || path.contains("pinentry") || path.contains("notification.") || path.contains("notificationrow") || key.contains("fingerprint") || key.contains("password"))return NONE
        val shortcut=key in setOf("start_button","end_button","left_button","right_button","keyguard_left_button","keyguard_right_button","flashlight_button","camera_button","affordance_background_circle","bottom_start","bottom_end")
        if((shortcut || path.contains("com.oplus.keyguard.ui.base.widget.opluslaunchableimageview")) && path.contains("keyguard"))return SHORTCUT
        if(trustedRole==CARD)return if(!textView && (background || clickable) && aspect in .55f..2.2f)CARD else NONE
        val self=names.firstOrNull()?.substringBefore('#')?.lowercase() ?: ""
        val clock=path.contains("keyguard") && (self.contains("clock") || key.contains("clock") ||
            key in setOf("hour","minute","time","date_view","date","lunar_date","weekday") || key.endsWith("_date"))
        if(trustedRole==TEXT || (textView || customGlyph) && clock)return TEXT
        val widgets=path.contains("keyguard") && (path.contains("stylewidget") || path.contains("keyguard.widget.") || path.contains("keyguard.widgets.") || path.contains("widgetplugin") || path.contains("#widget") || path.contains("_widget"))
        if(!textView && widgets && (background || clickable) && aspect in .55f..2.2f)return CARD
        return NONE
    }
    /** Center-cover mapping, shared by wallpaper read and the GPU material. */
    fun mapping(width:Int,height:Int,screenWidth:Int,screenHeight:Int):FloatArray {
        require(width>0 && height>0 && screenWidth>0 && screenHeight>0)
        val scale=maxOf(screenWidth.toFloat()/width,screenHeight.toFloat()/height)
        return floatArrayOf(1f/scale,(width-screenWidth/scale)*.5f,(height-screenHeight/scale)*.5f)
    }
}
