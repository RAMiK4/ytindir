# 4PDA post template

The post for a new YTindir release in the 4PDA thread (BBCode).

How to fill it in:

- `X.Y.Z`: the version, e.g. `2.15.0`
- `BUILD`: the GitHub Actions build number from the release tag (`vX.Y.Z-buildBUILD`)
- The "What's new" list: the Russian notes (`notes:ru`) from `release-notes/vX.Y.Z.md`, each line starting with `✓`
- The second section is `🔧 Изменения` when the release notes have a "Changes" section, `🐛 Исправления` for "Bug fixes"
- The arm64 APK (`YTindir-vX.Y.Z_arm64.apk`) is attached to the post on 4PDA
- `SIZE`: the universal APK's size in MB (decimal, as the release page shows it); the link always points to the latest release, so it never changes

```
[size=2][b][color="#00cc66"] YTindir[/color][/size][/b] vX.Y.Z ✓

[b]Тип:[/b] Новая версия
[b]Версия:[/b] X.Y.Z
[b]Краткое описание:[/b] YTindir - загрузчик видео и аудио на базе yt-dlp (build BUILD)

[spoiler=☑ Что нового в моде »]

[i]✨ Новые возможности[/i]
✓ …

[i]🔧 Изменения[/i]
✓ …
✓ Исправления ошибок
✓ Различные улучшения

[/spoiler]

[center][b]Скачать ↆ Download[/b][/center]

▷ [b][color="#00cc66"]YTindir[/color][/b] vX.Y.Z ✓

▷ [b][color="#00cc66"]YTindir[/color][/b] vX.Y.Z (universal) ✓
[url="https://github.com/RAMiK4/ytindir/releases/latest/download/YTindir-x-universal.apk"][color="#808080"]YTindir-vX.Y.Z_universal.apk[/color][/url] (SIZE МБ)
```

## Example: 2.15.0

```
[size=2][b][color="#00cc66"] YTindir[/color][/size][/b] v2.15.0 ✓

[b]Тип:[/b] Новая версия
[b]Версия:[/b] 2.15.0
[b]Краткое описание:[/b] YTindir - загрузчик видео и аудио на базе yt-dlp (build 78)

[spoiler=☑ Что нового в моде »]

[i]✨ Новые возможности[/i]
✓ Для ссылок YouTube нажатие на качество открывает новый расширенный список быстрой загрузки
✓ Варианты загрузки только аудио M4A (исходный звук), MP3 (Opus → MP3) и обложки (оригинальный JPG) для ссылок YouTube
✓ Значки качества 4K, 2K, FHD и HD в вариантах быстрой загрузки, с размером файла, кодеком и частотой кадров
✓ Новое расширенное меню быстрой загрузки внутри YouTube (будет доступно в новых версиях YouTube Morphe)
✓ В списке всех форматов видео с оригинальной озвучкой и дублированные видео сгруппированы отдельно
✓ SponsorBlock вернулся (Настройки → Обработка, по умолчанию выключен)

[i]🔧 Изменения[/i]
✓ Список форматов стал чище: аудио низкого качества, DRC-копии звука и видео ниже 480p скрыты
✓ Фильтр форматов и источники форматов удалены
✓ Старый ярлык «Быстрая загрузка» в меню «Поделиться» удалён
✓ Исправления ошибок
✓ Различные улучшения

[/spoiler]

[center][b]Скачать ↆ Download[/b][/center]

▷ [b][color="#00cc66"]YTindir[/color][/b] v2.15.0 ✓

▷ [b][color="#00cc66"]YTindir[/color][/b] v2.15.0 (universal) ✓
[url="https://github.com/RAMiK4/ytindir/releases/latest/download/YTindir-x-universal.apk"][color="#808080"]YTindir-v2.15.0_universal.apk[/color][/url] (196 МБ)
```
