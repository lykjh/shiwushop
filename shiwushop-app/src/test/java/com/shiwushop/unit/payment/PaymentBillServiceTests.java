package com.shiwushop.unit.payment;
import com.shiwushop.payment.entity.PaymentBill;
import com.shiwushop.payment.mapper.PaymentBillMapper;
import com.shiwushop.payment.service.BillDownloadGateway;
import com.shiwushop.payment.service.impl.BillFileDownloader;
import com.shiwushop.payment.service.impl.PaymentBillService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.nio.file.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class PaymentBillServiceTests {
 @TempDir Path root;
 @Test void databaseFailureRemovesNewFile() throws Exception {
  var bills=mock(PaymentBillMapper.class); var gateway=mock(BillDownloadGateway.class);
  var downloader=mock(BillFileDownloader.class); var manager=mock(PlatformTransactionManager.class);
  when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
  when(gateway.billDownloadUrl(any())).thenReturn("url"); when(downloader.download("url")).thenReturn(new byte[]{1,2});
  when(bills.insert(any(PaymentBill.class))).thenThrow(new DataAccessResourceFailureException("offline"));
  var service=new PaymentBillService(bills,gateway,downloader,manager,Clock.systemUTC());
  ReflectionTestUtils.setField(service,"storageRoot",root.toString());
  assertThrows(DataAccessResourceFailureException.class,()->service.download(LocalDate.of(2026,10,1),7L));
  try(var files=Files.list(root)) { assertEquals(0,files.count()); }
 }
}
