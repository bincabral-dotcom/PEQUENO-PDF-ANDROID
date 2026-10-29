package br.com.pdfcompactador

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.DecimalFormat
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var selectedFile: File
    private lateinit var fileNameLabel: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var targetInput: EditText
    private lateinit var dpiInput: EditText
    private lateinit var grayscale: CheckBox
    private lateinit var passwordInput: EditText
    private lateinit var newPasswordInput: EditText
    private lateinit var passwordChoice: RadioGroup
    private var originalSize = 0L
    private var selectedName = "PDF"

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copySelectedPdf(uri)
    }
    private val createPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) compressTo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        title = getString(R.string.app_name)
        setContentView(buildScreen())
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        }
        scroll.addView(root)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(dp(20), bars.top + dp(18), dp(20), bars.bottom + dp(28))
            insets
        }

        root.addView(TextView(this).apply {
            text = "Seu PDF, mais leve."; textSize = 29f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(27, 43, 91))
        }, full())
        root.addView(TextView(this).apply {
            text = "Escolha o arquivo e defina o tamanho que precisa."; textSize = 16f; setTextColor(Color.rgb(95, 106, 135))
        }, full(bottom = 18))

        val fileCard = card()
        fileCard.addView(TextView(this).apply { text = "1  •  Escolha o PDF"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(27, 43, 91)) })
        fileCard.addView(TextView(this).apply { text = "O arquivo fica somente no seu aparelho."; textSize = 13f; setTextColor(Color.rgb(95, 106, 135)); setPadding(0, dp(4), 0, dp(12)) })
        fileCard.addView(primaryButton("Selecionar PDF") { openPdf.launch(arrayOf("application/pdf")) }, full())
        fileNameLabel = TextView(this).apply { text = "Nenhum PDF selecionado"; textSize = 15f; setTextColor(Color.rgb(49, 94, 251)); setPadding(0, dp(14), 0, dp(3)) }
        sizeLabel = TextView(this).apply { text = "Tamanho original: --"; textSize = 14f; setTextColor(Color.rgb(95, 106, 135)) }
        fileCard.addView(fileNameLabel); fileCard.addView(sizeLabel)
        root.addView(fileCard, full(bottom = 14))

        val settingsCard = card()
        settingsCard.addView(TextView(this).apply { text = "2  •  Ajuste a compactação"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(27, 43, 91)) })
        settingsCard.addView(TextView(this).apply { text = "Quanto menor o DPI, menor o arquivo."; textSize = 13f; setTextColor(Color.rgb(95, 106, 135)); setPadding(0, dp(4), 0, dp(8)) })
        settingsCard.addView(label("Tamanho máximo desejado"))
        targetInput = edit("2.00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        targetInput.hint = "Ex.: 2"; targetInput.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_menu_info_details, 0)
        settingsCard.addView(targetInput, full())
        settingsCard.addView(label("Qualidade inicial (DPI)"))
        dpiInput = edit("150", InputType.TYPE_CLASS_NUMBER)
        settingsCard.addView(dpiInput, full())
        grayscale = CheckBox(this).apply { text = "Usar tons de cinza"; setTextColor(Color.rgb(52, 63, 91)); setPadding(0, dp(8), 0, 0) }
        settingsCard.addView(grayscale)
        root.addView(settingsCard, full(bottom = 14))

        val protectedCard = card()
        val protectedToggle = CheckBox(this).apply { text = "Meu PDF tem senha"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(27, 43, 91)) }
        protectedCard.addView(protectedToggle)
        val passwordSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        passwordSection.addView(TextView(this).apply { text = "Informe a senha e escolha como o arquivo final será salvo."; textSize = 13f; setTextColor(Color.rgb(95, 106, 135)); setPadding(0, dp(3), 0, dp(6)) })
        passwordInput = edit("", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { hint = "Senha do PDF de entrada (se houver)" }
        passwordSection.addView(passwordInput, full())
        passwordChoice = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf("Remover senha no arquivo final", "Manter a mesma senha", "Usar outra senha").forEachIndexed { index, text ->
            passwordChoice.addView(RadioButton(this).apply { id = 100 + index; this.text = text; isChecked = index == 0 })
        }
        passwordSection.addView(passwordChoice)
        newPasswordInput = edit("", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { hint = "Nova senha (somente para ‘Usar outra senha’)" }
        passwordSection.addView(newPasswordInput, full())
        protectedCard.addView(passwordSection)
        protectedToggle.setOnCheckedChangeListener { _, checked -> passwordSection.visibility = if (checked) View.VISIBLE else View.GONE }
        root.addView(protectedCard, full(bottom = 16))

        root.addView(TextView(this).apply { text = "O app ajusta o DPI automaticamente caso o resultado não caiba no tamanho escolhido."; textSize = 13f; setTextColor(Color.rgb(95, 106, 135)); setPadding(dp(4), 0, dp(4), dp(10)) }, full())
        root.addView(primaryButton("Compactar e salvar") { requestOutput() }, full())
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progress, full(top = 16))
        statusLabel = TextView(this).apply { text = ""; textSize = 14f; setTextColor(Color.rgb(49, 94, 251)); setPadding(0, dp(10), 0, 0) }
        root.addView(statusLabel)
        return scroll
    }

    private fun copySelectedPdf(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                selectedName = displayName(uri).removeSuffix(".pdf")
                val file = File(cacheDir, "input_${System.currentTimeMillis()}.pdf")
                contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(file).use { input.copyTo(it) } }
                selectedFile = file; originalSize = file.length()
                withContext(Dispatchers.Main) {
                    fileNameLabel.text = "PDF: $selectedName"
                    sizeLabel.text = "Tamanho original: ${formatSize(originalSize)}"
                    targetInput.setText(DecimalFormat("0.00").format(originalSize.toDouble() / MB).replace(',', '.'))
                    statusLabel.text = "PDF carregado. Escolha o tamanho máximo e toque em compactar."
                }
            } catch (e: Exception) { showError("Não foi possível abrir o PDF: ${e.message}") }
        }
    }

    private fun requestOutput() {
        if (!::selectedFile.isInitialized) return showError("Selecione um PDF primeiro.")
        val target = targetInput.text.toString().replace(',', '.').toDoubleOrNull()
            ?: return showError("Informe um tamanho em MB válido.")
        if (target <= 0) return showError("O tamanho desejado deve ser maior que zero.")
        createPdf.launch("${selectedName}_comprimido.pdf")
    }

    private fun compressTo(destination: Uri) {
        val targetMb = targetInput.text.toString().replace(',', '.').toDoubleOrNull() ?: return
        val dpi = dpiInput.text.toString().toIntOrNull()?.coerceIn(25, 300) ?: return showError("Informe um DPI entre 25 e 300.")
        val option = passwordChoice.checkedRadioButtonId
        val inputPassword = passwordInput.text.toString()
        val outputPassword = when (option) {
            101 -> inputPassword
            102 -> newPasswordInput.text.toString().also { if (it.isBlank()) return showError("Informe a nova senha.") }
            else -> ""
        }
        progress.visibility = View.VISIBLE; progress.progress = 0; statusLabel.text = "Preparando compactação…"
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = compress(selectedFile, targetMb, dpi, grayscale.isChecked, inputPassword, outputPassword) { done, total, message ->
                    lifecycleScope.launch(Dispatchers.Main) { progress.progress = done * 100 / total; statusLabel.text = message }
                }
                contentResolver.openOutputStream(destination)?.use { out -> FileInputStream(result.file).use { it.copyTo(out) } }
                    ?: error("Não foi possível gravar o arquivo escolhido.")
                result.file.delete()
                withContext(Dispatchers.Main) {
                    progress.progress = 100
                    statusLabel.text = "Concluído: ${formatSize(result.size)}${if (result.size > targetMb * MB) " (acima do alvo)" else ""}"
                    AlertDialog.Builder(this@MainActivity).setTitle("PDF salvo")
                        .setMessage("Original: ${formatSize(originalSize)}\nFinal: ${formatSize(result.size)}\nDPI usado: ${result.dpi}\n\nO arquivo foi salvo no local escolhido.")
                        .setPositiveButton("OK", null).show()
                }
            } catch (e: Exception) { showError("Falha na compactação: ${e.message}") }
            finally { withContext(Dispatchers.Main) { progress.visibility = View.GONE } }
        }
    }

    private fun compress(input: File, targetMb: Double, initialDpi: Int, gray: Boolean, inputPassword: String, outputPassword: String,
                         update: (Int, Int, String) -> Unit): Result {
        val targetBytes = (targetMb * MB).toLong()
        var best: Result? = null
        var testedDpi = initialDpi
        // Tenta o DPI escolhido e até quatro valores menores; preserva o maior DPI que atinge o alvo.
        for (step in 0 until 5) {
            val dpi = (initialDpi - step * ((initialDpi - 50).coerceAtLeast(10) / 4)).coerceAtLeast(50)
            testedDpi = dpi
            var low = 10; var high = 92; var bestAtDpi: Result? = null
            repeat(7) {
                val quality = (low + high) / 2
                val candidate = File.createTempFile("compactado_", ".pdf", cacheDir)
                rebuild(input, candidate, dpi, quality, gray, inputPassword, outputPassword) { done, total ->
                    update(done, total, "Testando ${dpi} DPI, qualidade $quality…")
                }
                val result = Result(candidate, candidate.length(), dpi)
                if (result.size <= targetBytes) { bestAtDpi?.file?.delete(); bestAtDpi = result; low = quality + 1 } else { candidate.delete(); high = quality - 1 }
            }
            if (bestAtDpi != null) { best?.file?.delete(); best = bestAtDpi; break }
        }
        if (best != null) return best!!
        // Caso impossível, entrega a versão mais leve no menor DPI/qualidade.
        val fallback = File.createTempFile("compactado_", ".pdf", cacheDir)
        rebuild(input, fallback, testedDpi, 10, gray, inputPassword, outputPassword) { done, total -> update(done, total, "Gerando melhor resultado possível…") }
        return Result(fallback, fallback.length(), testedDpi)
    }

    private fun rebuild(input: File, output: File, dpi: Int, quality: Int, gray: Boolean, inputPassword: String, outputPassword: String,
                        update: (Int, Int) -> Unit) {
        PDDocument.load(input, inputPassword).use { source ->
            PDDocument().use { destination ->
                val renderer = PDFRenderer(source)
                for (index in 0 until source.numberOfPages) {
                    val sourcePage = source.getPage(index)
                    val bitmap = renderer.renderImageWithDPI(index, dpi.toFloat(), if (gray) ImageType.GRAY else ImageType.RGB)
                    val image = JPEGFactory.createFromImage(destination, bitmap, quality.toFloat() / 100f)
                    val box = sourcePage.mediaBox
                    val page = PDPage(PDRectangle(box.width, box.height)); destination.addPage(page)
                    PDPageContentStream(destination, page).use { stream -> stream.drawImage(image, 0f, 0f, box.width, box.height) }
                    bitmap.recycle(); update(index + 1, source.numberOfPages)
                }
                if (outputPassword.isNotBlank()) {
                    val policy = StandardProtectionPolicy(outputPassword, outputPassword, AccessPermission())
                    policy.encryptionKeyLength = 256; destination.protect(policy)
                }
                destination.save(output)
            }
        }
    }

    private data class Result(val file: File, val size: Long, val dpi: Int)
    private fun displayName(uri: Uri): String = contentResolver.query(uri, null, null, null, null)?.use { c ->
        val column = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && column >= 0) c.getString(column) else "PDF"
    } ?: "PDF"
    private fun showError(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            statusLabel.text = message
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Atenção")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }
    private fun label(text: String) = TextView(this).apply { this.text = text; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(52, 63, 91)); setPadding(0, dp(12), 0, dp(2)) }
    private fun edit(value: String, type: Int) = EditText(this).apply { setText(value); inputType = type }
    private fun wide() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun full(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16))
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.rgb(228, 232, 244))
        }
        elevation = dp(2).toFloat()
    }
    private fun primaryButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text.uppercase(); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.rgb(49, 94, 251)); cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(12), dp(13), dp(12), dp(13))
        setOnClickListener { action() }
    }
    private fun divider() = Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(12)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun formatSize(bytes: Long): String = if (bytes < MB) "${DecimalFormat("0.00").format(bytes / 1024.0)} KB" else "${DecimalFormat("0.00").format(bytes / MB)} MB"
    companion object { const val MB = 1024.0 * 1024.0 }
}
