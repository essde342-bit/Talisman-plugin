# Server Plugins — Paper 1.21.4

В репозитории находятся независимые плагины для Paper 1.21.4. Они собираются вместе, но устанавливаются отдельными JAR-файлами.

## RustWeapons

Добавляет Rust-подобное оружие с 2D-моделями, отдельным отображением предмета в GUI и в руке (через item-model display context в 1.21.4), hitscan-выстрелами, магазинами и перезарядкой.

Оружие: Semi-Automatic Rifle, Assault Rifle (AK), Thompson, Custom SMG, Pump Shotgun, Double Barrel, Bolt Action Rifle, M92.

- ПКМ — выстрел.
- /rw reload — перезарядить оружие в основной руке.
- /rw give <игрок> <semi|ak|thompson|smg|pump|double|bolt|m92> [кол-во] — выдать оружие.
- /rw ammo <игрок> <rifle|pistol|shell> [кол-во] — выдать патроны.
- /rw list — список оружия.

Боеприпасы:
- rifle — винтовочные 5.56 (Semi, AK, Bolt).
- pistol — пистолетные (Thompson, SMG, M92).
- shell — дробовые 12 калибра (Pump, Double Barrel).

Право для выдачи предметов: rustweapons.admin (по умолчанию OP).

Resource Pack находится в RustWeapons/resource-pack. В нём для каждого оружия уже настроено переключение между отдельной моделью GUI и отдельной моделью руки. Сейчас в моделях стоят ванильные текстуры-заглушки, чтобы pack работал до добавления собственных PNG. Чтобы включить автоматическую отправку пака, укажи прямую HTTPS-ссылку в config.yml в resource-pack.url.

## Сборка

Нужны JDK 21 и Maven. В корне репозитория выполни:

    mvn --batch-mode clean package

GitHub Actions собирает отдельные JAR всех модулей, а также ZIP ресурс-пака RustWeapons.

## Установка

1. Скачай нужные JAR из последнего успешного запуска GitHub Actions.
2. Помести выбранные файлы в папку plugins сервера Paper 1.21.4.
3. Для RustWeapons загрузи RustWeapons-ResourcePack.zip на хостинг паков и укажи прямую HTTPS-ссылку в RustWeapons/config.yml (либо подключи pack вручную игрокам).
4. Перезапусти сервер.

Каждый модуль формирует собственный JAR. Устанавливать все плагины вместе необязательно.
