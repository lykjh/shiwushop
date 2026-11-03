package com.shiwushop.system.payment;
import com.shiwushop.support.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
/** 无真实支付宝凭据的系统实例只验证拒绝与私密文件访问控制。 */
class PaymentBillSystemIT {
 @Test void unconfiguredGatewayFailsAndPrivateBillRequiresAdministrator() throws Exception {
  String base=SystemEnvironment.base(); var jdbc=SystemEnvironment.database();
  try(var accounts=new TestAccounts(jdbc);
      var admin=CommerceFixture.login(base,accounts.create("ADMIN","Shop123!"));
      var user=CommerceFixture.login(base,accounts.create("USER","Shop123!"));
      var anonymous=new HttpAccountClient(base)) {
   String file="/admin/payment-bills/9223372036854775807/file";
   assertEquals(401,anonymous.get(file).statusCode());
   assertEquals(403,user.get(file).statusCode());
   assertEquals(404,admin.get(file).statusCode());
   var download=admin.post("/admin/payment-bills",Map.of("billDate",LocalDate.now().minusDays(1).toString()));
   assertEquals(503,download.statusCode(),download.body());
  }
 }
}
