# Temiz Seç 2.0

Android 8 ve üzeri için Türkçe dosya temizleme uygulaması.

[APK indir](https://github.com/oral1985/temiz-sec/releases/download/v2.0.0/Temiz-Sec.apk)

## Tarama

- **Telefonu tara:** Android 11 ve üzerinde “Tüm dosyalara erişim” ekranını açar. İzin verilince ortak depolamanın üst klasörü, alt klasörleri ve erişilebilen SD kartlar taranır. Android 8–10 için depolama izni istenir.
- **Klasör ekle:** Birden fazla klasör kaydedilebilir. “Klasörleri tara” hepsini birlikte tarar. Aynı dosyaya çıkan iç içe klasörler tekilleştirilir. Klasör listesinde seçimler kaldırılabilir.
- Android/data, Android/obb ve uygulamaların özel alanlarına erişim garanti edilmez. Okunamayan alanların sayısı gösterilir.
- Tarama durdurulabilir. Boş dosyalar ve bilinmeyen boyutlar eşleştirilmez.

## Eşleştirme ve seçim

Varsayılan olarak isimden bağımsız **tam bayt boyutu aynı** dosyalar gruplanır. Aynı isim, farklı isim ve yaklaşık aynı 1 KB aralığı filtreleri bulunur. Aynı boyut aynı içerik anlamına gelmez; yaklaşık eşleşmeler özellikle kontrol edilmelidir.

Fotoğraflar sonuç listesinde küçük önizlemelerle gösterilir ve uygulama içinde büyütülür. Diğer dosyalar uygun görüntüleyiciyle açılır.

“Birini bırak, kalanları seç” her grupta en az bir dosya bırakır. “Silme · hatırla” işaretleri kalıcıdır, toplu seçimde atlanır ve ancak elle seçilirse silinebilir. Silmeden önce yollar ve elle seçilen korumalı dosyalar gösterilir. Taramadan sonra değişen dosyalar silme sırasında atlanır.

Silme kalıcıdır. Çöp kutusu ve internet izni yoktur.

## Kurulum ve imza

İlk sürümün CI imza anahtarı saklanmamıştı. 2.0 sürümü 1.0 üzerine yüklenmezse eski sürümü kaldırmak gerekir; bu işlem 1.0 kayıtlarını siler. 2.0 ile debug imza anahtarı Actions önbelleğinde saklanır. Önbellek kaybolursa aynı risk yeniden oluşur. Üretim sürümleri için kalıcı, özel bir imza anahtarı yönetimi gerekir.

## Doğrulama

CI, eşleştirme ve koruma kuralları için JVM testleri, APK derlemesi, Android lint ve APK imza doğrulaması çalıştırır. Fiziksel Android cihazdaki izinler ve farklı üreticilerin SD kart sağlayıcıları cihazda ayrıca kontrol edilmelidir.

```sh
gradle testDebugUnitTest assembleDebug lintDebug
```
