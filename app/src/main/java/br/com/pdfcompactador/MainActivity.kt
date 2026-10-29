package br.com.pdfcompactador

import android.graphics.Color
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
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20)); gravity = Gravity.CENTER_HORIZONTAL
        }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            text = "Compactador de PDF"; textSize = 26f; setTextColor(Color.rgb(0, 92, 170))
        })
        root.addView(TextView(this).apply {
            text = "Reduza PDFs escaneados mantendo o melhor resultado possível dentro do tamanho definido."; textSize = 15f
            setPadding(0, dp(8), 0, dp(16))
        })
        root.addView(Button(this).apply { text = "SELECIONAR PDF"; setOnClickListener { openPdf.launch(arrayOf("application/pdf")) } }, wide())
        fileNameLabel = TextView(this).apply { text = "Nenhum PDF selecionado"; textSize = 16f; setPadding(0, dp(12), 0, dp(4)) }
        sizeLabel = TextView(this).apply { text = "Tamanho original: --" }
        root.addView(fileNameLabel); root.addView(sizeLabel)
        root.addView(divider())

        root.addView(label("Tamanho máximo desejado (MB)"))
        targetInput = edit("2.00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        root.addView(targetInput, wide())
        root.addView(label("DPI inicial (quanto menor, menor o arquivo)"))
        dpiInput = edit("150", InputType.TYPE_CLASS_NUMBER)
        root.addView(dpiInput, wide())
        grayscale = CheckBox(this).apply { text = "Converter para tons de cinza" }
        root.addView(grayscale)
        root.addView(divider())

        root.addView(label("PDF protegido por senha"))
        passwordInput = edit("", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { hint = "Senha do PDF de entrada (se houver)" }
        root.addView(passwordInput, wide())
        passwordChoice = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf("Remover senha do arquivo final", "Manter a mesma senha", "Usar outra senha").forEachIndexed { index, text ->
            passwordChoice.addView(RadioButton(this).apply { id = 100 + index; this.text = text; isChecked = index == 0 })
        }
        root.addView(passwordChoice)
        newPasswordInput = edit("", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { hint = "Nova senha (somente para ‘Usar outra senha’)" }
        root.addView(newPasswordInput, wide())
        root.addView(TextView(this).apply {
            text = "Se o resultado não atingir o tamanho pedido, o aplicativo testa automaticamente DPIs menores."; textSize = 13f
            setPadding(0, dp(10), 0, dp(10))
        })
        root.addView(Button(this).apply {
            text = "COMPACTAR E SALVAR"; setOnClickListener { requestOutput() }
        }, wide())
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progress, wide().apply { topMargin = dp(14) })
        statusLabel = TextView(this).apply { text = ""; setPadding(0, dp(10), 0, dp(16)) }
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
    private fun label(text: String) = TextView(this).apply { this.text = text; textSize = 15f; setPadding(0, dp(10), 0, dp(3)) }
    private fun edit(value: String, type: Int) = EditText(this).apply { setText(value); inputType = type }
    private fun wide() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun divider() = Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(12)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun formatSize(bytes: Long): String = if (bytes < MB) "${DecimalFormat("0.00").format(bytes / 1024.0)} KB" else "${DecimalFormat("0.00").format(bytes / MB)} MB"
    companion object { const val MB = 1024.0 * 1024.0 }
}
