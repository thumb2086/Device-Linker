# Add project specific ProGuard rules here.
# You can find more information about how to configure ProGuard here:
# https://www.guardsquare.com/en/products/proguard/manual/introduction

# Web3j / BouncyCastle rules
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider
-dontwarn org.bouncycastle.**
