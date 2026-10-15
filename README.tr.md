<h1 align="center">YTindr</h1>

<h3 align="center">
	Sade, hızlı ve kendini onaran Android ses/video indirici
</h3>

<div align="center">

[![İndir](https://img.shields.io/github/v/release/RAMiK4/ytindr?style=for-the-badge&label=%C4%B0ndir&color=00C46A)](https://github.com/RAMiK4/ytindr/releases/latest)
![Android](https://img.shields.io/badge/Android-7.0%2B-00C46A?style=for-the-badge&logo=android&logoColor=white)
![Lisans](https://img.shields.io/badge/Lisans-GPL--3.0-2C2C2C?style=for-the-badge)

</div>

<p align="center"><a href="README.md">English</a> · <b>Türkçe</b></p>

YTindr, [yt-dlp](https://github.com/yt-dlp/yt-dlp) kullanan kişisel bir indirme uygulamasıdır. yt-dlp'nin desteklediği [1000'den fazla siteden](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md) ses ve video indirebilir. [YTDLnis](https://github.com/deniscerri/ytdlnis) tabanlıdır; gereksiz özellikleri gizlenmiş, görünümü yenilenmiş ve sitelerdeki değişikliklere karşı kendi kendini güncelleyecek şekilde ayarlanmıştır.

## ✨ Özellikler

- 🎵 **Ses ve video indirme**, format ve kalite seçimiyle, 1000'den fazla siteden
- ✂️ **Kesme**: dakika ve saniye girerek videonun sadece bir bölümünü indirme
- 📤 **Paylaş menüsünden indirme**: bir uygulamada "Paylaş"a basıp linki direkt indirme
- 🔁 **Kendini onarma**: yt-dlp nightly kanalından otomatik güncellenir; bir indirme hata verirse (örneğin bir site değişikliği yüzünden) yt-dlp'yi güncelleyip indirmeyi bir kez daha kendiliğinden dener
- 📋 **Hata kaydını tek dokunuşla kopyalama**
- 🛡️ **YouTube PO token** desteği (BgUtils) ve indirmesi zor videolar için **özel yt-dlp kaynakları**
- 🎤 **Senkronize şarkı sözleri**: lrclib.net'ten bulunur, uygulamada önizlenir ve ses dosyasına gömülür
- 🍪 Çerez desteği: kendi hesabınızla açtığınız içerikler için
- 🎨 Kendi **YTindr** renk tonu ya da **Material You**; açık ve koyu tema, yüksek kontrast modu
- 📶 Wi-Fi ve mobil veride indirme
- 🔄 Uygulama içi güncelleme: yeni sürüm çıkınca uygulama haber verir
- 🌍 12 dil: İngilizce, Türkçe, Rusça, İspanyolca, Portekizce (Brezilya), Almanca, Fransızca, Arapça, Endonezce, Çince, Japonca ve Korece

## 🙈 Gizlenen özellikler

Arayüzü sade tutmak için şunlar gizlendi: terminal, komut şablonları, kaynak izleme, zamanlanmış indirme, SponsorBlock, altyazı, ekstra yt-dlp komutları ve gelişmiş yt-dlp ayarları. Kod içinde duruyorlar; ileride gerekirse tekrar açılabilirler.

## 📥 Kurulum

En son APK'yı **[Releases](https://github.com/RAMiK4/ytindr/releases/latest)** sayfasından indirin. Paket adı `com.rl.ytindr` olduğu için orijinal YTDLnis ile yan yana kurulabilir. Tüm sürümler aynı anahtarla imzalanır; yeni sürümler eskisinin üzerine kurulur.

Her `main` güncellemesinde GitHub Actions yeni bir arm64 APK derleyip otomatik olarak yayınlar.

## 🙏 Teşekkürler ve lisans

- **[YTDLnis](https://github.com/deniscerri/ytdlnis)**: Denis Çerri ve katkıda bulunanlar. YTindr bu projenin değiştirilmiş bir sürümüdür.
- **[yt-dlp](https://github.com/yt-dlp/yt-dlp)** ve **[youtubedl-android](https://github.com/yausername/youtubedl-android)**

Bu proje, orijinali gibi **[GPL-3.0](LICENSE)** lisansı altındadır.

## ⚖️ Sorumluluk

YTindr yalnızca indirme hakkınız olan içerikler için tasarlanmıştır. Kullandığınız sitelerin kullanım koşullarına ve telif haklarına uymak kullanıcının sorumluluğundadır.
