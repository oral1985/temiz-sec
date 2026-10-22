# Temiz Seç 3.0.1

[Android APK indir](https://github.com/oral1985/temiz-sec/releases/download/v3.0.1/Temiz-Sec.apk)

Telefonu tara düğmesiyle dosya erişimine izin verin veya klasörler ekleyin. Tarama sırasında dosyalar listelenir, aynı boyuttaki adayların tamamı SHA-256 ile okunup karşılaştırılır, ardından fotoğraf benzerliği incelenir. Büyük video ve fotoğraf koleksiyonlarında içerik taraması zaman alır; aşama ve ilerleme gösterilir, tarama durdurulabilir.

## Kategoriler

- **Birebir kopyalar:** İsimden bağımsız, tam içeriği aynı fotoğraf, video ve diğer dosyalar. Aynı boyutta farklı içerikler birlikte gösterilmez.
- **Benzer fotoğraflar:** Boyutları farklı olsa bile görüntü benzerliği olan fotoğraflar. dHash, ortalama parlaklık örüntüsü, renk ve en/boy oranı karşılaştırılır. Az ayrıntılı düz görüntüler yanlış eşleşmemeleri için atlanır. Her grup bir temsilciyle karşılaştırılır; benzerlik zincirleri birleştirilmez. Bu yöntem bir tahmindir, her benzer görseli bulma garantisi yoktur. Videolarda görsel benzerlik uygulanmaz.
- **Eski dosyalar:** Son değişikliği 180 günden eski dosyalar. Gereksiz oldukları varsayılmaz.
- **Geçici / boş dosyalar:** Son 48 saatte değişmemiş boş ve .tmp/.temp/.part/.crdownload/.download dosyaları; cache/temp klasörlerindeki eski dosya adayları. .nomedia gibi kontrol dosyaları hariç tutulur. Dosyanın yaşı ve adı gereksiz olduğunu garanti etmez.
- **Büyük dosyalar:** En az 100 MB olan dosyalar.

Kopya ve benzer gruplarda bir dosya bırakılabilir, diğerleri toplu seçilip tek tek değiştirilebilir. Diğer kategorilerde kullanıcı tek tek seçim yapar. “Silme · hatırla” işaretleri cihazda saklanır, toplu seçilmez; ancak elle seçilip silme onayında ayrıca belirtilirse silinebilir. Hiçbir dosya otomatik silinmez. Silme kalıcıdır.

Telefon taraması ortak depolamayı ve erişilebilen SD kartları kapsar. Android/data, Android/obb ve özel uygulama alanları kapsam dışıdır. Uygulama başka uygulamaların özel önbelleğini veya RAM'ini temizleyemez. Birden fazla seçili klasör birlikte taranabilir, iç içe seçimler tekilleştirilir. İçeriği okunamayan/değişen dosyalar eşleştirilmez.

Önceki sürümde debug anahtarı standart olduğu varsayılan konuma yazılmadığı için önbelleğe alınmamıştı. 3.0.1, açıkça .signing/debug.keystore konumunu kullanır ve bu dosyayı Actions önbelleğinde saklar. 2.0 üzerine kurulumda paket çakışması olursa eski sürüm kaldırılmalıdır; bu işlem eski kayıtları siler. Önbellek GitHub tarafından silinirse anahtar yeniden oluşur. Kalıcı üretim imza anahtarı henüz yapılandırılmamıştır. Özel anahtar kaynak kod deposuna eklenmez.

## Denetimler

```sh
bash scripts/prepare-debug-key.sh
gradle testDebugUnitTest assembleDebug lintDebug
```

JVM testleri: gerçek içerik karşılaştırması, aynı boyutta farklı içerik, SHA-256 referans özeti, iptal, okunamayan/değişen dosyalar, görsel benzerlik ve renk/örüntü farkları, farklı boyutlu benzer fotoğraflar, yaş/geçicilik ayrımı, kopya başına bir dosya bırakma ve kalıcı koruma seçimleri. CI APK imzasını da doğrular. Fiziksel cihaz testi henüz yapılmamıştır.
