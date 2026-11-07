package ua.kpi.radmonitor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class DocumentTest {
 @Test fun createsThreePagePdf(){
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val rows=(1..45).map{Measurement(id="pdf-$it",name="Пост $it",doseRate=it*.01)}
  val file=FilesService.pdf(c,rows)
  assertTrue(file.length()>1000)
  ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{renderer->assertEquals(3,renderer.pageCount)}}
 }
}
