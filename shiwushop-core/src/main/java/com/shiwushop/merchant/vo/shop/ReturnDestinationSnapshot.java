package com.shiwushop.merchant.vo.shop;

public record ReturnDestinationSnapshot(Long shopId, int keyVersion, byte[] consigneeCiphertext, byte[] phoneCiphertext,
                                        String province, String city, String district, byte[] detailCiphertext) {}
