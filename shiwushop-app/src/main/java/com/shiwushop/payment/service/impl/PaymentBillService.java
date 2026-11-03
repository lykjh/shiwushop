package com.shiwushop.payment.service.impl;

import com.shiwushop.common.BusinessException;
import com.shiwushop.common.logging.BusinessEventLog;
import com.shiwushop.payment.entity.PaymentBill;
import com.shiwushop.payment.mapper.PaymentBillMapper;
import com.shiwushop.payment.service.BillDownloadGateway;
import com.shiwushop.payment.vo.PaymentBillVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class PaymentBillService {
 private static final Logger log=LoggerFactory.getLogger(PaymentBillService.class);
 private final PaymentBillMapper bills;
 private final BillDownloadGateway gateway;
 private final BillFileDownloader downloader;
 private final PlatformTransactionManager transactions;
 private final Clock businessClock;
 @Value("${shop.payment.bill-root:./local-data/payment-bills}") private String storageRoot;
 @Value("${shop.payment.bill-max-bytes:10485760}") private long maxBytes=10485760;
 public PaymentBillService(PaymentBillMapper bills,BillDownloadGateway gateway,BillFileDownloader downloader,
   PlatformTransactionManager transactions,Clock businessClock) {
  this.bills=bills; this.gateway=gateway; this.downloader=downloader; this.transactions=transactions; this.businessClock=businessClock;
 }
 /** 外部下载和文件写入位于事务外，数据库唯一约束仲裁并发账单。 */
 public PaymentBillVO download(LocalDate date,Long actor) {
  byte[] data=downloader.download(gateway.billDownloadUrl(date));
  String hash;
  try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
  catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  String key=UUID.randomUUID().toString();
  Path root=Path.of(storageRoot).toAbsolutePath().normalize();
  Path file=root.resolve(key);
  boolean kept=false, created=false;
  try {
   Files.createDirectories(root);
   Files.createFile(file);
   created=true;
   Files.write(file,data,StandardOpenOption.WRITE);
   PaymentBill bill=new PaymentBill();
   bill.setChannel(gateway.channel()); bill.setAppId(gateway.appId()); bill.setSellerId(gateway.sellerId());
   bill.setBillType("trade"); bill.setBillDate(date); bill.setSha256(hash); bill.setStorageKey(key);
   bill.setByteSize((long)data.length); bill.setStatus("DOWNLOADED_UNPARSED"); bill.setDownloadedBy(actor);
   bill.setDownloadedAt(LocalDateTime.now(businessClock));
   try {
    new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
     bills.insert(bill);
     BusinessEventLog.committed("payment","bill_downloaded","payment_bill",bill.getId());
    });
    kept=true;
    return PaymentBillVO.from(bills.selectById(bill.getId()));
   } catch (DuplicateKeyException e) {
    PaymentBill previous=bills.findDaily(gateway.channel(),gateway.appId(),gateway.sellerId(),date);
    if (previous==null || !hash.equals(previous.getSha256())) throw new BusinessException(409,"该日期账单内容冲突");
    return PaymentBillVO.from(previous);
   }
  } catch (IOException e) { throw new BusinessException(503,"账单无法保存",e); }
  finally {
   if (created && !kept) {
    try { Files.deleteIfExists(file); }
    catch (IOException e) { log.warn("账单临时文件清理失败 key={} error={}",key,e.getClass().getSimpleName()); }
   }
  }
 }
 /** 管理员凭批次查询摘要，不暴露内部存储键。 */
 public PaymentBillVO detail(Long id) { return PaymentBillVO.from(requireBill(id)); }
 /** 管理员按批次读取私密原文件，验证随机键、规范路径及文件大小。 */
 public FileSystemResource file(Long id) {
  PaymentBill bill=requireBill(id);
  String key=bill.getStorageKey();
  if (!key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
   throw new BusinessException(503,"账单暂时无法读取");
  Path root=Path.of(storageRoot).toAbsolutePath().normalize(), file=root.resolve(key).normalize();
  if (!file.getParent().equals(root) || Files.isSymbolicLink(file)) throw new BusinessException(503,"账单暂时无法读取");
  try {
   if (!Files.isRegularFile(file) || Files.size(file)!=bill.getByteSize() || bill.getByteSize()<1 || bill.getByteSize()>maxBytes)
    throw new BusinessException(503,"账单暂时无法读取");
   return new FileSystemResource(file);
  } catch (IOException e) { throw new BusinessException(503,"账单暂时无法读取",e); }
 }
 private PaymentBill requireBill(Long id) {
  PaymentBill bill=bills.selectById(id);
  if (bill==null) throw new BusinessException(404,"账单不存在");
  return bill;
 }
}
