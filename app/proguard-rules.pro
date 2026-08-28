# Add project specific ProGuard rules here.
# TFLite model files in assets are accessed at runtime; no keep rules needed for the model itself.
-keep class org.tensorflow.lite.** { *; }
