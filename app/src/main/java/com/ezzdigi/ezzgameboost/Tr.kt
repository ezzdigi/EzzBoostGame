package com.ezzdigi.ezzgameboost

import android.Manifest
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.net.Uri
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object Tr {
    @Volatile
    var en = false

    fun load(ctx: Context) {
        en = ctx.getSharedPreferences("ezzgameboost", Context.MODE_PRIVATE)
            .getString("lang", "fa") == "en"
    }

    fun t(s: String): String {
        if (!en) return s
        val ex = EXACT[s]
        if (ex != null) return ex
        var out = s
        for (p in EXTRA) {
            if (out.indexOf(p.first) >= 0) out = out.replace(p.first, p.second)
        }
        for (p in PAIRS) {
            if (out.indexOf(p.first) >= 0) out = out.replace(p.first, p.second)
        }
        return out
    }

    // Whole-string translations (short labels, buttons).
    private val EXACT: Map<String, String> = mapOf(
        "🌐 به‌روزرسانی لیست DNS آنلاین" to "🌐 Update online DNS list",
        "📋 کلیپ‌بورد" to "📋 Clipboard",
        "هسته‌ی Xray در این نسخه نیست. نسخه‌ی Release را نصب کن." to "The Xray core is not included in this build. Install the Release version.",
        "اجازه‌ی VPN داده نشد؛ بدون آن اتصال V2Ray ممکن نیست." to "VPN permission denied; V2Ray cannot connect without it.",
        "در حال اتصال V2Ray…" to "Connecting V2Ray…",
        "شروع سرویس ناموفق بود." to "Starting the service failed.",
        "کانفیگ‌های V2Ray/Xray را از کلیپ‌بورد، فایل (لینک‌ها یا لینک اشتراک) یا لیست آنلاین اضافه کن، تست کن و مستقیم با دکمه‌ی «اتصال» وصل شو (vmess، vless، trojan، ss). پروتکل‌های UDP مثل hysteria2 و tuic از طریق کلاینت V2Ray باز می‌شوند." to
            "Add V2Ray/Xray configs from the clipboard, a file (links or a subscription link) or the online list, test them and connect directly with the Connect button (vmess, vless, trojan, ss). UDP protocols such as hysteria2 and tuic open through your V2Ray client.",
        "📁 فایل" to "📁 File",
        "🌐 لیست آنلاین" to "🌐 Online list",
        "🧪 تست" to "🧪 Test",
        "اتصال با کلاینت" to "Open in client",
        "کپی" to "Copy",
        "کلیپ‌بورد خالی است" to "Clipboard is empty",
        "هیچ کانفیگ معتبری پیدا نشد" to "No valid config found",
        "این پروتکل UDP است؛ تست TCP معنی ندارد." to "This protocol uses UDP; a TCP test is not meaningful.",
        "پاسخ نداد" to "No response",
        "لینک کپی شد و در کلاینت باز شد" to "Link copied and opened in your client",
        "لینک کپی شد؛ داخل کلاینت از کلیپ‌بورد وارد کن" to "Link copied; import it from the clipboard inside your client",
        "کلاینت V2Ray نصب نیست؛ لینک کپی شد." to "No V2Ray client installed; link copied.",
        "کانفیگ‌های V2Ray/Xray را از کلیپ‌بورد، فایل (لینک‌ها یا لینک اشتراک) یا لیست آنلاین اضافه کن، تست کن و با یک لمس در کلاینت V2Ray خودت (v2rayNG، Hiddify، NekoBox...) باز کن. اتصال داخل کلاینت انجام می‌شود، نه داخل این اپ." to
            "Add V2Ray/Xray configs from the clipboard, a file (links or a subscription link) or the online list, test them, and open them in your own V2Ray client (v2rayNG, Hiddify, NekoBox...) with one tap. The connection runs inside the client, not inside this app.",
        "فقط لینک‌های اشتراکی (vmess، vless، trojan، ss، hysteria2، tuic) یا لینک اشتراک پشتیبانی می‌شوند. فایل JSON کامل Xray را مستقیم داخل کلاینت وارد کن." to
            "Only share links (vmess, vless, trojan, ss, hysteria2, tuic) or subscription links are supported. Import full Xray JSON files directly inside your client.",
        "در حال دریافت…" to "Fetching…",
        "لیست به‌روز شد" to "List updated",
        "دریافت لیست ناموفق بود" to "Fetching the list failed",
        "⬇️ دانلود آپدیت" to "⬇️ Download update",
        "🔄 بررسی آپدیت" to "🔄 Check for updates",
        "در حال بررسی…" to "Checking…",
        "آخرین نسخه را داری ✅" to "You have the latest version ✅",
        "بررسی آپدیت ناموفق بود" to "Update check failed",
        "✈️ کانال تلگرام ایزی دیجی" to "✈️ EzzDigi Telegram channel",
        "بدون تغییر" to "No change",
        "بدون وایرگارد" to "No WireGuard",
        "وایرگارد" to "WireGuard",
        "پاک‌سازی RAM قبل از اجرا" to "Clean RAM before launch",
        "روشن ماندن صفحه" to "Keep screen on",
        "ذخیره پروفایل" to "Save profile",
        "حذف پروفایل" to "Delete profile",
        "بستن" to "Close",
        "پروفایل ذخیره شد" to "Profile saved",
        "پروفایل حذف شد" to "Profile deleted",
        "روشن" to "On",
        "خاموش" to "Off",
        "🎮 فقط بازی‌ها (بقیه مستقیم)" to "🎮 Games only (others direct)",
        "📱 کل گوشی" to "📱 Whole phone",
        "هنوز به‌روز نشده" to "Not updated yet",
        "🎛 پروفایل برای هر بازی: DNS یا وایرگارد مخصوص، پاک‌سازی RAM و اتصال خودکار" to
            "🎛 Per-game profiles: dedicated DNS or WireGuard, RAM cleanup and auto-connect",
        "💡 «فقط بازی‌ها» وایرگارد را فقط برای بازی‌ها و علاقه‌مندی‌های تو فعال می‌کند؛ بقیه‌ی برنامه‌ها مستقیم می‌روند. با زدن روی حالت، تغییر می‌کند." to
            "💡 “Games only” routes only your games and favorites through WireGuard; other apps go direct. Tap the mode to switch."
    )

    // Longer fragments used inside composed strings.
    private val EXTRA: List<Pair<String, String>> = listOf(
        Pair("🆕 نسخه جدید موجود است:", "🆕 New version available:"),
        Pair("هسته‌ی Xray داخل این نسخه نیست؛ برای اتصال مستقیم، نسخه‌ی Release را نصب کن.", "The Xray core is not in this build; install the Release version for direct connection."),
        Pair("ساخت پیکربندی ناموفق:", "Config build failed:"),
        Pair("🟢 V2Ray متصل:", "🟢 V2Ray connected:"),
        Pair("⚠️ خطای V2Ray:", "⚠️ V2Ray error:"),
        Pair("هسته‌ی Xray:", "Xray core:"),
        Pair("کلاینت V2Ray نصب نیست", "No V2Ray client installed"),
        Pair("کلاینت نصب‌شده:", "Installed client:"),
        Pair("کانفیگ اضافه شد:", "Configs added:"),
        Pair("هنوز به‌روز نشده", "Not updated yet"),
        Pair("نسخه جدید موجود است:", "New version available:"),
        Pair("⬇️ دانلود نسخه", "⬇️ Download version"),
        Pair("آخرین به‌روزرسانی:", "Last update:"),
        Pair("لیست آنلاین:", "Online list:"),
        Pair("پروفایل بازی:", "Game profile:"),
        Pair("نسخه فعلی:", "Current version:")
    )

    // Persian fragment -> English; longest fragments first.
    private val PAIRS: List<Pair<String, String>> = listOf(
        Pair("DNS دلخواهت را دستی وارد کن؛ آدرس IPv4 و IPv6 هر دو پشتیبانی می‌شوند. اول تست کن، بعد ذخیره کن و وصل شو", "Enter your own DNS manually; both IPv4 and IPv6 addresses are supported. Test first, then save and connect"),
        Pair("IPv6 فقط وقتی کار می‌کند که اینترنت شبکه‌ات IPv6 داشته باشد؛ اگر تست آن ✕ شد، از IPv4 استفاده کن. DNS ذخیره‌شده در لیست «DNS CHANGER» (تب DNS) هم دیده می‌شود", "IPv6 only works when your network has IPv6; if its test shows ✕, use IPv4. Saved DNS also appears in the “DNS CHANGER” list (DNS tab)"),
        Pair("IPv4 اصلی  (مثل 1.1.1.1)", "Primary IPv4  (e.g. 1.1.1.1)"),
        Pair("IPv4 دوم  (مثل 1.0.0.1)", "Secondary IPv4  (e.g. 1.0.0.1)"),
        Pair("IPv6 اصلی  (مثل 2606:4700:4700::1111)", "Primary IPv6  (e.g. 2606:4700:4700::1111)"),
        Pair("IPv6 دوم  (مثل 2606:4700:4700::1001)", "Secondary IPv6  (e.g. 2606:4700:4700::1001)"),
        Pair("حداکثر ۲۰ DNS دلخواه قابل ذخیره است", "You can save up to 20 custom DNS entries"),
        Pair("هنوز DNS دلخواهی ذخیره نشده", "No custom DNS saved yet"),
        Pair("حداقل یک آدرس DNS وارد کن", "Enter at least one DNS address"),
        Pair("🧪 تست آدرس‌ها", "🧪 Test addresses"),
        Pair("💾 ذخیره‌ی DNS", "💾 Save DNS"),
        Pair("آدرس نامعتبر است", "invalid address"),
        Pair("DNS ذخیره شد", "DNS saved"),
        Pair("نام (اختیاری)", "Name (optional)"),
        Pair("هنوز کانفیگی اضافه نشده", "No configs added yet"),
        Pair("کانفیگ وایرگارد نامعتبر است:", "Invalid WireGuard config:"),
        Pair("اجازه\u200cی VPN داده نشد؛ بدون آن اتصال ممکن نیست", "VPN permission was not granted; connecting is not possible without it"),
        Pair("فایل خوانده نشد یا خیلی بزرگ است", "The file could not be read or is too large"),
        Pair("کانفیگ وایرگارد در کلیپ\u200cبورد پیدا نشد", "No WireGuard config found on the clipboard"),
        Pair("نام کانفیگ (اختیاری)", "Config name (optional)"),
        Pair("خطای وایرگارد:", "WireGuard error:"),
        Pair("وایرگارد قطع شد", "WireGuard disconnected"),
        Pair("کانفیگ ذخیره شد", "Config saved"),
        Pair("کانفیگ اضافه شد", "configs added"),
        Pair("حداکثر ۲۰ کانفیگ وایرگارد قابل ذخیره است", "You can save up to 20 WireGuard configs"),
        Pair("افزودن فایل .conf", "Add .conf file"),
        Pair("لینک GitHub (فایل .conf، پوشه یا ریپو)", "GitHub link (.conf file, folder or repo)"),
        Pair("دریافت سرور گیمینگ وایرگارد", "Get WireGuard gaming servers"),
        Pair("سرور گیمینگ", "Gaming Server"),
        Pair("🆕 جدید", "🆕 New"),
        Pair("با زدن «کپی» نام میزبان کپی می‌شود؛ آن را در Private DNS وارد کن.", "Tap Copy to copy the hostname, then paste it into Private DNS."),
        Pair("آدرس ریپو معتبر نیست؛ مثل user/repo بنویس.", "Invalid repo; use the form user/repo."),
        Pair("ذخیره شد؛ در حال به‌روزرسانی…", "Saved; updating…"),
        Pair("💾 ذخیره و به‌روزرسانی", "💾 Save and update"),
        Pair("لیست DNS ناموفق بود", "DNS list failed"),
        Pair("آخرین خطای اپ", "Last app error"),
        Pair("کپی", "Copy"),
        Pair("بستن", "Close"),
        Pair("🧪 تست", "🧪 Test"),
        Pair("اجازه‌ی VPN داده نشد؛ بدون آن تست ممکن نیست.", "VPN permission was not granted; the test is not possible without it."),
        Pair("🎮 فقط بازی‌ها", "🎮 Games only"),
        Pair("در حال دریافت سرورهای گیمینگ…", "Fetching gaming servers…"),
        Pair("لیست سرورها دریافت نشد.", "Could not get the server list."),
        Pair("لیست سرورها معتبر نیست.", "The server list is not valid."),
        Pair("سرور گیمینگ معتبری پیدا نشد.", "No valid gaming server found."),
        Pair("لینک GitHub (اختیاری: فایل .conf، پوشه یا ریپو)", "GitHub link (optional: .conf file, folder or repo)"),
        Pair("لینک GitHub را وارد کن.", "Enter a GitHub link."),
        Pair("در حال دریافت از GitHub…", "Fetching from GitHub…"),
        Pair("لیست فایل‌ها از GitHub دریافت نشد.", "Could not get the file list from GitHub."),
        Pair("هیچ فایل .conf در این مسیر پیدا نشد.", "No .conf file found at this path."),
        Pair("کانفیگ معتبر وایرگارد پیدا نشد.", "No valid WireGuard config found."),
        Pair("دریافت از GitHub ناموفق بود.", "GitHub import failed."),
        Pair("بازی‌ای پیدا نشد. یک بازی نصب کن یا آن را در تب بازی‌ها علاقه‌مند کن.", "No game found. Install a game or favorite it in the Games tab."),
        Pair("از کلیپ\u200cبورد", "From clipboard"),
        Pair("آپلود فایل", "Upload file"),
        Pair("چسباندن لینک", "Paste link"),
        Pair("متصل شد", "Connected"),
        Pair("این اپ بدون روت کار می\u200cکند. دکمه\u200cی BOOST فقط از API رسمی اندروید برای بستن پردازش\u200cهای پس\u200cزمینه استفاده می\u200cکند و اندروید ممکن است بعضی برنامه\u200cها را دوباره اجرا کند. هیچ اپ معمولی نمی\u200cتواند FPS یا پینگ داخل بازی را تضمین کند؛ تست شبکه فقط زمان اتصال TCP را اندازه می\u200cگیرد", "This app works without root. The BOOST button only uses Android's official API to close background processes, and Android may restart some apps. No regular app can guarantee FPS or in-game ping; the network test only measures TCP connection time"),
        Pair("اتصال با یک VPN محلی انجام می\u200cشود که فقط درخواست\u200cهای DNS را به سرور انتخابی می\u200cفرستد؛ بقیه\u200cی ترافیک از مسیر عادی می\u200cرود. اندروید هم\u200cزمان فقط یک VPN را نگه می\u200cدارد، پس VPN دیگر قطع می\u200cشود. اگر Private DNS روی حالت hostname است، اول آن را Off یا Automatic کن", "The connection uses a local VPN that only sends DNS requests to the selected server; all other traffic takes its normal path. Android keeps only one VPN at a time, so your other VPN will disconnect. If Private DNS is set to a hostname, switch it to Off or Automatic first"),
        Pair("روش دوم: DNS-over-TLS داخل تنظیمات خود اندروید. نام میزبان را کپی کن و در Private DNS وارد کن. اگر اتصال قطع شد، گزینه را روی Automatic برگردان", "Second method: DNS-over-TLS inside Android's own settings. Copy the hostname and enter it in Private DNS. If the connection breaks, set the option back to Automatic"),
        Pair("حریم خصوصی: برای تغییر DNS یک VPN محلی ساخته می\u200cشود. فقط درخواست\u200cهای DNS به سرور انتخابی تو می\u200cرود و هیچ داده\u200cای برای ما ارسال نمی\u200cشود", "Privacy: a local VPN is created to change DNS. Only DNS requests go to the server you choose, and no data is sent to us"),
        Pair("اگر قبلاً یک\u200cبار DNS را وصل کرده باشی، با اجرای بازی از همین اپ، DNS انتخابی خودکار وصل می\u200cشود", "If you have connected DNS once before, launching a game from this app auto-connects your selected DNS"),
        Pair("با یک لمس، پردازش\u200cهای پس\u200cزمینه\u200cی برنامه\u200cها بسته می\u200cشوند تا RAM آزاد بیشتری برای بازی بماند", "With one tap, apps' background processes are closed to leave more free RAM for gaming"),
        Pair("هنوز داده\u200cای نیست. بازی\u200cها را از تب «بازی\u200cها» اجرا کن تا زمان بازی ثبت شود", "No data yet. Launch games from the “Games” tab to record play time"),
        Pair("زمان برقراری اتصال TCP تا سرور انتخابی؛ پینگ ICMP یا پینگ داخل بازی نیست", "Time to open a TCP connection to the selected server; this is not ICMP ping or in-game ping"),
        Pair("زمان\u200cها تقریبی\u200cاند: از لحظه\u200cی اجرا تا بازگشت به این اپ حساب می\u200cشود", "Times are approximate: counted from launch until you return to this app"),
        Pair("میان\u200cبرهای مهم برای گیمینگ؛ تنظیمات سیستم را مستقیم باز می\u200cکنند", "Handy gaming shortcuts; they open system settings directly"),
        Pair("؛ ابزار گیمینگ اندروید: بوست RAM، DNS گیمینگ و تست پینگ", "; Android gaming toolkit: RAM boost, gaming DNS and ping test"),
        Pair("تغییر واقعی DNS با رادار گیم، الکترو، شکن، بگذر و شلتر", "Real DNS switching with Radar Game, Electro, Shecan, Begzar and Shelter"),
        Pair("تست پینگ، ذخیره\u200cی سرورهای بازی و تاریخچه\u200cی کیفیت شبکه", "Ping test, saved game servers and network quality history"),
        Pair("بازی\u200cای شناسایی نشد. روی «همه» بزن و بازی\u200cهایت را با", "No games detected. Tap “All” and mark your games with"),
        Pair("هنوز بازی مورد علاقه\u200cای نداری. در تب «بازی\u200cها» روی", "You have no favorite games yet. In the “Games” tab tap"),
        Pair("برنامه بررسی شد؛ تغییر محسوسی در RAM آزاد دیده نشد", "apps checked; no noticeable change in free RAM"),
        Pair("هنوز تستی برای این سرور ثبت نشده؛ یک تست پینگ بگیر", "No test recorded for this server yet; run a ping test"),
        Pair("فقط داخل همین اپ؛ روشنایی کل سیستم تغییر نمی\u200cکند", "Only inside this app; the system brightness is not changed"),
        Pair("اجازه\u200cی VPN داده نشد؛ بدون آن DNS تغییر نمی\u200cکند", "VPN permission was not granted; DNS cannot change without it"),
        Pair("کتابخانه\u200cی بازی\u200cها با زمان بازی و اتصال خودکار", "Game library with play time and auto-connect"),
        Pair("اندروید معمولاً خودش RAM را خوب مدیریت می\u200cکند", "Android usually manages RAM well on its own"),
        Pair("برای اتصال خودکار، یک\u200cبار DNS را دستی وصل کن", "For auto-connect, connect DNS manually once first"),
        Pair("هیچ DNS ای پاسخ نداد؛ اینترنت را بررسی کن", "No DNS responded; check your internet"),
        Pair("فقط سرورهای ذخیره\u200cشده\u200cی خودت حذف می\u200cشوند", "Only your own saved servers can be deleted"),
        Pair("این دستگاه از VPN محلی پشتیبانی نمی\u200cکند", "This device does not support a local VPN"),
        Pair("تا وقتی این اپ بازه، صفحه خاموش نمی\u200cشود", "The screen will not turn off while this app is open"),
        Pair("کاور ضخیم را بردار تا گوشی خنک\u200cتر بماند", "Remove thick cases so the phone stays cooler"),
        Pair("یک DNS انتخاب کن و دکمه\u200cی اتصال را بزن", "Pick a DNS and tap Connect"),
        Pair("پایدار؛ دانلودهای پس\u200cزمینه را متوقف کن", "stable; pause background downloads"),
        Pair("آدرس سرور را به شکل host:port وارد کن", "Enter the server address as host:port"),
        Pair("در حال پاک\u200cسازی پردازش\u200cهای پس\u200cزمینه", "Cleaning background processes"),
        Pair("مرورگری برای باز کردن لینک پیدا نشد", "No browser found to open the link"),
        Pair("مورد دیگر… برای پیداکردن، جستجو کن", "more… search to find them"),
        Pair("برنامه\u200cهای سنگین را از Recent ببند", "Close heavy apps from Recent"),
        Pair("باتری بالای ۳۰٪ یا گوشی روی شارژر", "Battery above 30% or phone on charger"),
        Pair("در حال بارگذاری فهرست برنامه\u200cها", "Loading the app list"),
        Pair("همه\u200cی تست\u200cها بدون پاسخ بوده\u200cاند", "All tests got no response"),
        Pair("حداکثر ۱۵ سرور قابل ذخیره است", "You can save up to 15 servers"),
        Pair("سریع\u200cترین را پیدا کن و وصل شو", "Find the fastest and connect"),
        Pair("حالت «مزاحم نشوید» را روشن کن", "Turn on “Do Not Disturb” mode"),
        Pair("فهرست علاقه\u200cمندی\u200cها خالی است", "Your favorites list is empty"),
        Pair("آدرس سرور  host:port  (مثلاً", "Server address  host:port  (e.g."),
        Pair("بوست RAM و پایش زنده\u200cی گوشی", "RAM boost and live phone monitoring"),
        Pair("بزن تا اینجا میان\u200cبر بسازی", "to create a shortcut here"),
        Pair("اجرای این برنامه ممکن نیست", "This app cannot be launched"),
        Pair("به علاقه\u200cمندی\u200cها اضافه کن", "to add them to favorites"),
        Pair("شروع سرویس DNS ناموفق بود", "Failed to start the DNS service"),
        Pair("مسدودسازی دامنه\u200cهای مخرب", "Blocks malicious domains"),
        Pair("ساخته\u200cشده توسط ایزی دیجی", "Built by EzzDigi"),
        Pair("قطع (DNS پیش\u200cفرض سیستم", "Off (default system DNS"),
        Pair("جستجوی بازی یا برنامه", "Search games or apps"),
        Pair("درخواست پاسخ\u200cداده\u200cشده", "Answered queries"),
        Pair("به حالت پیش\u200cفرض برگشت", "reverted to default"),
        Pair("گزینه\u200cهای توسعه\u200cدهنده", "Developer options"),
        Pair("نام سرور (برای ذخیره", "Server name (to save"),
        Pair("تست سرعت همه\u200cی DNSها", "Speed-test all DNS servers"),
        Pair("روشن نگه\u200cداشتن صفحه", "Keep screen on"),
        Pair("پاک کردن آمار بازی", "Clear game stats"),
        Pair("زمان بازی ثبت\u200cشده", "Recorded play time"),
        Pair("هسته\u200cهای پردازنده", "CPU cores"),
        Pair("اتصال برقرار نشد", "Could not connect"),
        Pair("حذف سرور انتخابی", "Delete selected server"),
        Pair("پاک کردن تاریخچه", "Clear history"),
        Pair("مدیریت برنامه\u200cها", "Manage apps"),
        Pair("بار اجرا از طریق", "launches via"),
        Pair("نرخ نوسازی صفحه", "Refresh rate"),
        Pair("برنامه بررسی شد", "apps checked"),
        Pair("تست تأخیر اتصال", "TCP connection latency test"),
        Pair("کیفیت شبکه برای", "Network quality for"),
        Pair("در حال اتصال به", "Connecting to"),
        Pair("اشتراک\u200cگذاری اپ", "Share app"),
        Pair("موردی پیدا نشد", "Nothing found"),
        Pair("هنوز اجرا نشده", "Not launched yet"),
        Pair("حداکثر روشنایی", "Max brightness"),
        Pair("پیش\u200cفرض سیستم", "System default"),
        Pair("علاقه\u200cمندی\u200cها", "Favorites"),
        Pair("سرور ذخیره شد", "Server saved"),
        Pair("شروع تست پینگ", "Start ping test"),
        Pair("فیلتر تبلیغات", "Ad filtering"),
        Pair("ذخیره\u200cی سرور", "Save server"),
        Pair("سریع و عمومی", "Fast & public"),
        Pair("اتصال خودکار", "Auto-connect"),
        Pair("آماده نبرد؟", "Ready for battle?"),
        Pair("وضعیت دمایی", "Thermal status"),
        Pair("کیفیت اتصال", "Connection quality"),
        Pair("بهترین ساعت", "Best hour"),
        Pair("بدترین ساعت", "Worst hour"),
        Pair("آماده\u200cی تست", "Ready to test"),
        Pair("مزاحم نشوید", "Do Not Disturb"),
        Pair("در اندروید", "in Android"),
        Pair("بدون اتصال", "No connection"),
        Pair("دمای باتری", "Battery temp"),
        Pair("در حال تست", "Testing"),
        Pair("ایزی دیجی", "EzzDigi"),
        Pair("قطع اتصال", "Disconnect"),
        Pair("سریع\u200cترین", "Fastest"),
        Pair("فیروزه\u200cای", "Cyan"),
        Pair("باز کردن", "Open"),
        Pair("خیلی گرم", "Hot"),
        Pair("بار اجرا", "launches"),
        Pair("تغییر به", "Switch to"),
        Pair("اتصال به", "Connect to"),
        Pair("رنگ فعلی", "Current color"),
        Pair("بازی\u200cها", "Games"),
        Pair("کمی گرم", "Slightly warm"),
        Pair("اضطراری", "Emergency"),
        Pair("اندروید", "Android"),
        Pair("آزاد شد", "freed"),
        Pair("نمایشگر", "Display"),
        Pair("نارنجی", "Orange"),
        Pair("ساعت و", "h"),
        Pair("کپی شد", "Copied"),
        Pair("نامشخص", "Unknown"),
        Pair("بحرانی", "Critical"),
        Pair("موبایل", "Mobile"),
        Pair("دستگاه", "Device"),
        Pair("ناموفق", "Failed"),
        Pair("رنگ تم", "Theme color"),
        Pair("ابزار", "Tools"),
        Pair("دقیقه", "min"),
        Pair("متوسط", "Average"),
        Pair("عمومی", "Public"),
        Pair("اتصال", "Connect"),
        Pair("خاموش", "Off"),
        Pair("باتری", "Battery"),
        Pair("حافظه", "Storage"),
        Pair("قرمز", "Red"),
        Pair("بنفش", "Purple"),
        Pair("خانه", "Home"),
        Pair("شبکه", "Network"),
        Pair("آمار", "Stats"),
        Pair("عادی", "Normal"),
        Pair("شارژ", "Charging"),
        Pair("آزاد", "free"),
        Pair("اجرا", "Launch"),
        Pair("عالی", "Excellent"),
        Pair("ضعیف", "Weak"),
        Pair("توقف", "Stop"),
        Pair("متصل", "Connected"),
        Pair("شروع", "Start"),
        Pair("روشن", "On"),
        Pair("سبز", "Green"),
        Pair("گرم", "Warm"),
        Pair("همه", "All"),
        Pair("خوب", "Good"),
        Pair("تست", "tests"),
        Pair("کپی", "Copy"),
        Pair("صدا", "Sound")
    )
}
