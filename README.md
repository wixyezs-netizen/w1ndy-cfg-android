# W1NDY CFG

Android-клиент сервиса W1NDY CFG.

## Основано на sing-box

W1NDY CFG — это модифицированная версия официального Android-клиента
[sing-box-for-android](https://github.com/SagerNet/sing-box-for-android) (ядро [sing-box](https://github.com/SagerNet/sing-box) v1.14.3).
Проект не связан с SagerNet и не одобрен ими. Все права на оригинальный код принадлежат его авторам.

Лицензия: **GNU General Public License v3.0 или новее** — см. [LICENSE](LICENSE).
Исходный код каждой опубликованной сборки доступен в этом репозитории (тег совпадает с версией приложения).

## Изменения относительно оригинала

- Новое название (W1NDY CFG), иконка и идентификатор приложения `com.w1ndy.cfg`.
- Проверка обновлений использует релизы этого репозитория.
- Удалён ключ подписи оригинального проекта; сборки подписываются собственным ключом.
- Сборка ядра и приложения выполняется в GitHub Actions (`.github/workflows/build.yml`).

## Сборка

Сборка выполняется автоматически в GitHub Actions. Для локальной сборки следуйте
[инструкции оригинального проекта](https://sing-box.sagernet.org/installation/build-from-source/),
затем положите `libbox.aar` в `app/libs` и выполните `./gradlew :app:assembleOtherRelease`.
