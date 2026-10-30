package com.coloros16.liquidglass.particles

import android.app.Activity
import android.os.*
import android.util.Log
import android.widget.FrameLayout
import android.widget.TextView
import com.coloros16.liquidglass.hook.ParticleDissolveHook

/** Synthetic shade lifecycle only; does not clear any real notification. */
class ClearAllCloseProbeActivity:Activity() {
    private val ui=Handler(Looper.getMainLooper())
    private val api=ParticleDissolveHook.Api33
    private lateinit var root:FrameLayout
    private lateinit var label:TextView
    private var calls=0
    private fun step(delay:Long=0,block:()->Unit) { ui.postDelayed({
        try { block() } catch(error:Throwable) { Log.e("ClearAllCloseProbe","FAIL",error);label.text="FAIL $error" }
    },delay) }
    override fun onCreate(saved:Bundle?) {
        super.onCreate(saved)
        root=FrameLayout(this);label=TextView(this).apply { text="Testing clear-all shade lifecycle" }
        root.addView(label);setContentView(root)
        root.post { step {
            check(api.deferClearAll(root,180) { calls++ })
            check(calls==0)
            // Repeated native completion must not add a second timer or collapse twice.
            check(api.deferClearAll(root,180) { calls+=100 })
            check(api.deferClearAll(root,0) { calls+=100 }) // Effect ended while native callback repeated.
            step(300) {
                check(calls==1)
                check(api.deferClearAll(root,180) { calls+=100 })
                api.cancelShadeClose(root) // Manual close, followed by reopening.
                step(300) {
                    check(calls==1)
                    val child=FrameLayout(this);root.addView(child,FrameLayout.LayoutParams(30,30))
                    check(api.deferClearAll(child,180) { calls+=100 })
                    root.removeView(child)
                    step(300) {
                        check(calls==1)
                        check(api.deferClearAll(root,900) { calls++ })
                        api.stopAll();check(calls==2)
                        step(1000) {
                            check(calls==2)
                            check(!api.deferClearAll(root,0) { calls+=100 })
                            Log.i("ClearAllCloseProbe","PASS: native completion once; manual close/detach cancel; disable releases immediately; no stale callback")
                            label.text="PASS · clear-all shade lifecycle"
                        }
                    }
                }
            }
        } }
    }
    override fun onDestroy() { api.cancelShadeClose(root);ui.removeCallbacksAndMessages(null);super.onDestroy() }
}
