# Temiz Seç

Android 8 ve üzeri için Türkçe dosya seçme ve temizleme uygulaması.

- Kullanıcının izin verdiği klasörü ve alt klasörlerini tarar.
- Tam bayt boyutu ve aynı ada göre dosyaları gruplar.
- Farklı adlı, aynı boyutlu dosyaları ayrı filtrede gösterir.
- Her grupta bir dosyayı bırakıp diğerlerini topluca seçer; tek tek seçim değiştirilebilir.
- “Silme · hatırla” işaretleri cihazda saklanır ve toplu seçimde atlanır. Kullanıcı ayrıca elle seçebilir.
- Fotoğraflar ve diğer dosyalar telefondaki görüntüleyici ile açılır.
- Silmeden önce seçilen yolları ve elle seçilmiş korumalı dosyaları göstererek onay ister.
- İnternet izni, reklam veya otomatik silme yoktur.

Aynı boyut aynı içerik anlamına gelmez. Silme kalıcıdır; uygulamada çöp kutusu yoktur.
Android bazı kök ve özel klasörlere erişim vermez. Bir defada seçilen tek klasör taranır.

## APK indirme

Actions → APK oluştur → başarılı çalışma → Artifacts → Temiz-Sec-APK.
İndirilen ZIP içindeki Temiz-Sec.apk dosyasını kurun.

## Derleme

JDK 17, Gradle 8.9, Android SDK 35 gereklidir.

```sh
gradle assembleDebug lintDebug
```

İlk sürüm debug anahtarıyla imzalanır. Farklı CI çalışmaları farklı debug anahtarları
oluşturabilir; sonraki APK güncelleme olarak yüklenmeyebilir. Uygulamayı kaldırmak
kayıtlı klasör izinlerini ve “silme” işaretlerini sıfırlar.

Otomatik derleme ve lint denetimi fiziksel cihaz testinin yerini tutmaz.
