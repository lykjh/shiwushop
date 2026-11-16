package com.shiwushop.unit.payment;

import com.shiwushop.common.BusinessException;
import com.shiwushop.payment.entity.PaymentBill;
import com.shiwushop.payment.mapper.PaymentBillMapper;
import com.shiwushop.payment.service.BillDownloadGateway;
import com.shiwushop.payment.service.impl.BillFileDownloader;
import com.shiwushop.payment.service.impl.PaymentBillServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentBillServiceTests {
    @TempDir
    Path root;

    @Test
    void databaseFailureRemovesNewFile() throws Exception {
        var bills = mock(PaymentBillMapper.class);
        var gateway = mock(BillDownloadGateway.class);
        var downloader = mock(BillFileDownloader.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(gateway.billDownloadUrl(any())).thenReturn("url");
        when(downloader.download("url")).thenReturn(new byte[]{1, 2});
        when(bills.insert(any(PaymentBill.class))).thenThrow(new DataAccessResourceFailureException("offline"));
        var service = new PaymentBillServiceImpl(bills, gateway, downloader, manager, Clock.systemUTC());
        ReflectionTestUtils.setField(service, "storageRoot", root.toString());
        assertThrows(DataAccessResourceFailureException.class, () -> service.download(LocalDate.of(2026, 10, 1), 7L));
        try (var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void readingRejectsSameSizeFileWithDifferentHash() throws Exception {
        var bills = mock(PaymentBillMapper.class);
        var bill = new PaymentBill();
        bill.setId(7L);
        bill.setStorageKey(UUID.randomUUID().toString());
        bill.setByteSize(3L);
        bill.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[]{1, 2, 3})));
        when(bills.selectById(7L)).thenReturn(bill);
        Files.write(root.resolve(bill.getStorageKey()), new byte[]{1, 2, 4});
        var service = new PaymentBillServiceImpl(bills, mock(BillDownloadGateway.class),
                mock(BillFileDownloader.class), mock(PlatformTransactionManager.class), Clock.systemUTC());
        ReflectionTestUtils.setField(service, "storageRoot", root.toString());
        assertEquals(503, assertThrows(BusinessException.class, () -> service.file(7L)).getCode());
    }
}
