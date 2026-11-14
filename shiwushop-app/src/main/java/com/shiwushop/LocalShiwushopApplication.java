package com.shiwushop;

import java.io.IOException;
import java.util.Arrays;

/** Windows IDEA 本地入口；每次预检通过后才启动本次应用。 */
public class LocalShiwushopApplication {

    public static void main(String[] args) throws IOException, InterruptedException {
        int exit = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", "deploy/rocketmq/start-local.ps1", "-MessagingOnly")
                .inheritIO().start().waitFor();
        if (exit != 0)
            throw new IllegalStateException("本地 RocketMQ 预检失败，应用未启动；请查看上方预检输出");

        String[] verified = Arrays.copyOf(args, args.length + 2);
        verified[args.length] = "--shop.shipment.rocket-name-server=localhost:29876";
        verified[args.length + 1] = "--shop.shipment.rocket-preflight-verified=true";
        ShiwushopApplication.main(verified);
    }
}
