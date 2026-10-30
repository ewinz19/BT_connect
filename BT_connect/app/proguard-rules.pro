# ========================================
# UMUM
# ========================================
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable,Signature,RuntimeVisibleAnnotations

# Hindari penghapusan log saat debugging
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# Keep semua class dan method yang diberi anotasi @Keep
-keep @androidx.annotation.Keep class *
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Keep konstruktor (penting jika digunakan dalam refleksi/layout XML)
-keepclassmembers class * {
    public <init>(...);
}

# Untuk onClick handler di layout XML
-keepclassmembers class * {
    public void onClick(android.view.View);
}

# ========================================
# ANDROIDX SUPPORT
# ========================================

# Core KTX
-keep class androidx.core.** { *; }
-dontwarn androidx.core.**

# AppCompat
-keep class androidx.appcompat.** { *; }
-dontwarn androidx.appcompat.**

# Material Components
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# RecyclerView
-keep class androidx.recyclerview.** { *; }
-dontwarn androidx.recyclerview.**

# ViewPager2
-keep class androidx.viewpager2.** { *; }
-dontwarn androidx.viewpager2.**

# ========================================
# TEST LIBS (tidak perlu dijaga, tapi jangan beri warning)
-dontwarn org.junit.**