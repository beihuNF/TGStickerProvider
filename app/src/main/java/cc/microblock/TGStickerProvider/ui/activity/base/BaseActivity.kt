@file:Suppress("MemberVisibilityCanBePrivate", "UNCHECKED_CAST")

package cc.microblock.TGStickerProvider.ui.activity.base

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.viewbinding.ViewBinding
import cc.microblock.TGStickerProvider.R
import cc.microblock.TGStickerProvider.utils.factory.isNotSystemInDarkMode
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.type.android.LayoutInflaterClass

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {

    /**
     * 视图绑定对象
     */
    lateinit var binding: VB

    /**
     * 配置变更重建时暂存的实例状态，供子类在 onCreate 中读取
     */
    protected var restoredInstanceState: Bundle? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = current().generic()?.argument()?.method {
            name = "inflate"
            param(LayoutInflaterClass)
        }?.get()?.invoke<VB>(layoutInflater) ?: error("binding failed")
        setContentView(binding.root)
        // 隐藏系统默认标题栏
        supportActionBar?.hide()
        // 适配状态栏与导航栏前景深浅色及背景色
        applySystemBarsAppearance()
        restoredInstanceState = savedInstanceState
        try {
            onCreate()
        } finally {
            restoredInstanceState = null
        }
    }

    /**
     * 配置状态栏与导航栏图标明暗及低版本背景色
     */
    private fun applySystemBarsAppearance() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = isNotSystemInDarkMode
            isAppearanceLightNavigationBars = isNotSystemInDarkMode
        }
        // Android 15 (API 35) 起系统栏强制透明，仅在低版本设置背景色以消除废弃告警
        if (Build.VERSION.SDK_INT < 35) {
            val bgColor = ResourcesCompat.getColor(resources, R.color.colorThemeBackground, null)
            @Suppress("DEPRECATION")
            window?.statusBarColor = bgColor
            @Suppress("DEPRECATION")
            window?.navigationBarColor = bgColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                window?.navigationBarDividerColor = bgColor
            }
        }
    }

    /**
     * 子类初始化入口
     */
    abstract fun onCreate()
}
