package com.example.customerarchive

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val cameraRequest = 410
    private val permissionRequest = 411
    private var selectedSide = "الوجه الأول"
    private var photoUri: Uri? = null
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 36, 28, 36)
            setBackgroundColor(0xFFF5F7F6.toInt())
        }
        val title = TextView(this).apply {
            text = "أرشيف بطاقات الزبائن"
            textSize = 25f
            setTextColor(0xFF075E54.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "صوّر كل وجه، وسيُحفظ تلقائياً باسم الشخص"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 30)
        }
        val first = makeButton("📸  تصوير الوجه الأول")
        first.setOnClickListener { startCapture("الوجه الأول") }
        val second = makeButton("📸  تصوير الوجه الثاني")
        second.setOnClickListener { startCapture("الوجه الثاني") }
        status = TextView(this).apply {
            text = "جاهز للتصوير"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        }
        root.addView(title, matchWrap())
        root.addView(subtitle, matchWrap())
        root.addView(first, buttonParams())
        root.addView(second, buttonParams())
        root.addView(status, matchWrap())
        setContentView(root)
    }

    private fun makeButton(label: String) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        setTextColor(0xFFFFFFFF.toInt())
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF075E54.toInt())
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun buttonParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 12, 0, 12) }

    private fun startCapture(side: String) {
        selectedSide = side
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), permissionRequest)
            return
        }
        openCamera()
    }

    private fun openCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "لا يوجد تطبيق كاميرا متاح", Toast.LENGTH_LONG).show()
            return
        }
        val dir = File(cacheDir, "captures").apply { mkdirs() }
        val temp = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        photoUri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.provider", temp
        )
        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, cameraRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
        else if (requestCode == permissionRequest) Toast.makeText(this, "يلزم السماح باستخدام الكاميرا", Toast.LENGTH_LONG).show()
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != cameraRequest || resultCode != Activity.RESULT_OK) return
        val uri = photoUri ?: return
        status.text = "تم التصوير؛ جارٍ استخراج الاسم والحفظ..."
        executor.execute {
            try {
                val source = File(cacheDir, "captures").listFiles()
                    ?.filter { it.name.startsWith("capture_") }
                    ?.maxByOrNull { it.lastModified() } ?: throw Exception("لم يتم العثور على الصورة")
                val name = extractName(source)
                val clean = sanitize(name.ifBlank { "اسم_غير_واضح" })
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "${clean}_${timestamp}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/بطاقات الزبائن")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val savedUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("تعذر إنشاء ملف الصورة")
                contentResolver.openOutputStream(savedUri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw Exception("تعذر كتابة الصورة")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(savedUri, values, null, null)
                runOnUiThread {
                    status.text = "تم حفظ $selectedSide باسم: $clean"
                    Toast.makeText(this, "تم الحفظ في Pictures/بطاقات الزبائن: $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "تعذر استخراج الاسم أو الحفظ: ${e.message}"
                    Toast.makeText(this, "حدث خطأ أثناء الحفظ", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun extractName(imageFile: File): String {
        val dataDir = File(filesDir, "tessdata").apply { mkdirs() }
        val trained = File(dataDir, "ara.traineddata")
        if (!trained.exists()) {
            runOnUiThread { status.text = "يتم تنزيل ملف اللغة العربية لأول مرة..." }
            val url = URL("https://github.com/tesseract-ocr/tessdata_fast/raw/main/ara.traineddata")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.inputStream.use { input -> trained.outputStream().use { output -> input.copyTo(output) } }
            connection.disconnect()
        }
        val api = TessBaseAPI()
        if (!api.init(filesDir.absolutePath, "ara")) throw Exception("تعذر تشغيل التعرف على العربية")
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: throw Exception("الصورة غير صالحة")
        api.setImage(bitmap)
        val text = api.utF8Text ?: ""
        api.end()
        bitmap.recycle()
        val candidates = text.lines().map { it.trim() }
            .filter { it.length >= 5 && it.any { ch -> ch in '\u0600'..'\u06FF' } }
        return candidates.maxByOrNull { line -> line.count { it in '\u0600'..'\u06FF' } } ?: ""
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\n\\r]+"), " ")
            .replace(Regex("\\s+"), " ").trim().take(70).ifBlank { "اسم_غير_واضح" }
}
