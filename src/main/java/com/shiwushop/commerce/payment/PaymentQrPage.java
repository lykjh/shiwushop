package com.shiwushop.commerce.payment;

import com.shiwushop.common.BusinessException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public final class PaymentQrPage {
    private PaymentQrPage() {}

    /** 将渠道二维码编码成内嵌 SVG 收银页；页面仅展示付款信息，不更新订单状态。 */
    public static String render(String qrCode, BigDecimal amount, LocalDateTime expiresAt, String channel) {
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(qrCode, BarcodeFormat.QR_CODE, 1, 1,
                    Map.of(EncodeHintType.MARGIN, 4, EncodeHintType.CHARACTER_SET, "UTF-8"));
            StringBuilder pixels = new StringBuilder();
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    if (matrix.get(x, y)) pixels.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
            String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" aria-label=\"支付宝付款二维码\" shape-rendering=\"crispEdges\" "
                    + "viewBox=\"0 0 " + matrix.getWidth() + " " + matrix.getHeight() + "\">"
                    + "<rect width=\"100%\" height=\"100%\" fill=\"white\"/>"
                    + "<path fill=\"black\" d=\"" + pixels + "\"/></svg>";
            boolean sandbox = "ALIPAY_SANDBOX".equals(channel);
            String title = sandbox ? "支付宝沙箱扫码支付" : "支付宝扫码支付";
            String instruction = sandbox ? "请使用支付宝沙箱版手机 App 扫码付款" : "请使用支付宝 App 扫码付款";
            return """
                    <!doctype html>
                    <html lang="zh-CN">
                    <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>%s</title>
                    <style>body{font-family:system-ui,sans-serif;background:#f5f6f8;color:#222;text-align:center;padding:40px 12px}
                    main{max-width:420px;margin:auto;background:white;padding:28px 16px;border-radius:12px}
                    svg{width:min(80vw,300px);height:min(80vw,300px);image-rendering:pixelated}
                    .amount{font-size:28px;font-weight:bold}p{line-height:1.6}</style></head>
                    <body><main><h1>%s</h1><p class="amount">¥%s</p>%s
                    <p>%s</p><p>订单付款截止时间：%s</p>
                    <p>付款后返回商城查询支付状态；此页面不会直接确认付款。</p></main></body></html>
                    """.formatted(title, title, amount.toPlainString(), svg, instruction,
                    expiresAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        } catch (WriterException e) {
            throw new BusinessException(503, "支付二维码生成失败", e);
        }
    }
}
