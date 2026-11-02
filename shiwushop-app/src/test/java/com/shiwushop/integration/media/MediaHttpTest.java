package com.shiwushop.integration.media;

import com.shiwushop.support.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.autoconfigure.exclude=","spring.datasource.hikari.maximum-pool-size=2"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MediaHttpTest {
    static final Path ROOT=Path.of("target","media-http-"+System.nanoTime()).toAbsolutePath().normalize();
    @DynamicPropertySource static void mediaRoot(DynamicPropertyRegistry registry){registry.add("shop.media.root",ROOT::toString);}
    @LocalServerPort int port; @Autowired JdbcTemplate jdbc;
    TestAccounts accounts; TestAccounts.Account buyer; HttpAccountClient client;
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void prepare() throws Exception {
        accounts=new TestAccounts(jdbc); buyer=accounts.create("USER","Shop123!");
        client=CommerceFixture.login("http://127.0.0.1:"+port,buyer);
    }
    @AfterEach void cleanup() throws Exception {
        client.close();
        for(String key:jdbc.queryForList("SELECT storage_key FROM media_assets WHERE uploaded_by_user_id=?",String.class,buyer.id())) {
            Path path=ROOT.resolve(key).normalize(); assertTrue(path.startsWith(ROOT)); Files.deleteIfExists(path);
        }
        jdbc.update("DELETE FROM media_assets WHERE uploaded_by_user_id=?",buyer.id()); accounts.close();
    }
    static byte[] png() throws Exception {
        var output=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",output); return output.toByteArray();
    }
    @Test void actualImageContentWinsOverSpoofedFilenameAndMime() throws Exception {
        var response=client.upload("/media",png(),"image.exe","text/plain"); assertEquals(200,response.statusCode(),response.body());
        long id=json.readTree(response.body()).path("data").path("id").asLong();
        assertEquals("image/png",jdbc.queryForObject("SELECT mime_type FROM media_assets WHERE id=?",String.class,id));
        var read=client.get("/media/"+id); assertEquals(200,read.statusCode());
        assertTrue(read.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        try(var anonymous=new HttpAccountClient("http://127.0.0.1:"+port)){assertEquals(404,anonymous.get("/media/"+id).statusCode());}
    }
    @Test void rejectsEmptyAndDamagedFilesWithoutPersistingRows() throws Exception {
        assertEquals(400,client.upload("/media",new byte[0],"empty.png","image/png").statusCode());
        assertEquals(400,client.upload("/media",new byte[]{1,2,3,4},"broken.png","image/png").statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_assets WHERE uploaded_by_user_id=?",Integer.class,buyer.id()));
    }
    @Test void fileAboveTenMiBReturns413Not500() throws Exception {
        var response=client.upload("/media",new byte[10*1024*1024+1],"large.png","image/png");
        assertEquals(413,response.statusCode(),response.body());
    }
    @Test void oneHundredUploadsCompleteThroughBoundedWaiting() throws Exception {
        var photo=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_RGB);
        var random=new Random(20261101L);
        for(int y=0;y<1024;y++)for(int x=0;x<1024;x++)photo.setRGB(x,y,random.nextInt(0x1000000));
        var encoded=new ByteArrayOutputStream();ImageIO.write(photo,"jpg",encoded);byte[] jpeg=encoded.toByteArray();photo.flush();
        try(var pool=Executors.newFixedThreadPool(12)) {
            List<Future<Integer>> results=new ArrayList<>(); long start=System.nanoTime();
            for(int i=0;i<100;i++) results.add(pool.submit(()->client.upload("/media",jpeg,"毛巾展示.jpg","image/jpeg").statusCode()));
            int accepted=0,rejected=0;
            for(var result:results) {int status=result.get(45,TimeUnit.SECONDS); if(status==200)accepted++;else if(status==429)rejected++;else fail("unexpected HTTP "+status);}
            assertEquals(100,accepted+rejected);
            assertEquals(100,accepted,"正常批量上传应排队完成");
            assertEquals(0,rejected,"12 个工作线程不应因两路解码许可被立即拒绝");
            assertEquals(accepted,jdbc.queryForObject("SELECT COUNT(*) FROM media_assets WHERE uploaded_by_user_id=?",Integer.class,buyer.id()));
            System.out.println("MEDIA_100 image=1024x1024 bytes="+jpeg.length+" accepted="+accepted+" rejected="+rejected+" elapsed_ms="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        }
    }
    @Test void twentyMillionPixelsAreAcceptedAndLargerImageIsRejected() throws Exception {
        for(int width:List.of(4000,4001)){
            var image=new BufferedImage(width,5000,BufferedImage.TYPE_INT_RGB);var output=new ByteArrayOutputStream();
            ImageIO.write(image,"png",output);image.flush();
            var response=client.upload("/media",output.toByteArray(),"large-dimensions.png","image/png");
            assertEquals(width==4000?200:413,response.statusCode(),response.body());
        }
    }
}
