# Проверки Minecraft 26.3

Набор проверок относится к Minecraft 26.3, NeoForge 26.3.0.99 и четырём активным
mod ID: `emi`, `iris`, `sodium`, `lanmc_pause_actions`. Источник состава —
`portable-pack.json` и `tools/distribution-lock.json`; исторические файлы находятся
отдельно в `tools/legacy-files.json`. Лаунчер доставляет e4steam отдельно.

## Статический аудит 5 ноября 2026 года

Аудит исходного коммита `66cea9b6413249c66c074529e2a890f12860a382` не запускал
тесты, проверки генератора, сборку, игру или публикацию. Числа ниже получены
разбором исходников Python, это количество методов `test_*`, а не результат
прогона или число отдельных `subTest`.

| Файл | До | После |
| --- | ---: | ---: |
| `tools/keybinds/test_preset.py` | 8 | 8 |
| `tools/test_logging_policy.py` | 12 | 12 |
| `tools/test_manifest_upgrade.py` | 4 | 4 |
| `tools/test_pack_library.py` | 10 | 8 |
| `tools/test_pause_actions.py` | 22 | 20 |
| Всего | 56 | 52 |

Библиотечный fixture использует текущие runtime и метаданные карточки вместо
условной NeoForge 1.21.1 и вымышленных родительских модов. Сохраняются стабильный
ID `minecraft-26-2`, название и путь `Minecraft 26.3`, официальные ссылки и ровно
четыре записи. Sodium моделирует реальный NeoForge wrapper с повторным тем же
mod ID во вложенном JAR; дубликат не должен добавлять пятый мод.

У текущей карточки пустые `libraryIds` и `parents`; `visibleModIds`, `modGroups`,
`baseModIds`, `modCountScope` и `library/loaded-mods.json` не заданы. Положительные
проверки посторонних схем библиотек и аддонов удалены. Проверка циклической
связи остаётся как отказ от некорректных метаданных; проверка необязательной
инвентаризации сохраняет отказ при устаревших SHA. Fabric-only вложение в
NeoForge fixture проверяет, что чужой формат не создаёт вымышленный loaded ID;
это не матрица поддержки Fabric. Подмена обязательного загрузчика тоже остаётся
отрицательным сценарием. Fixture политики логирования переведён на 26.3;
неподдерживаемая версия по-прежнему должна отклоняться без изменений файлов.

Удалены только следующие методы:

- `LibraryPublisherTests.test_embedded_mods_are_published_once_including_dependencies_of_normal_mods`:
  вымышленные `base`, `addon`, `nested_helper` отсутствуют в текущем составе.
  Реальное повторение mod ID Sodium покрывается общим fixture и
  `UpgradeManifestTests.test_wrapper_and_embedded_same_mod_id_are_one_locked_id`.
- `LibraryPublisherTests.test_non_mod_components_are_visible_libraries_without_fabric_or_invented_loaded_ids`:
  Scalable Cat's Force/LANGPROVIDER отсутствует в поставке; все четыре текущих
  mod ID являются загружаемыми модами. Их признаки проверяет основной сценарий.
- `PauseActionsPublicationTests.test_old_embedded_contract_cannot_restore_disconnected_native_publication`:
  одновременно менял три поля и дублировал отказ при отсутствующем discovery.
  Отсутствие и изменения discovery уже проверяются отдельно; прежние
  `modVersion` и `artifactPath` перенесены в независимые отрицательные варианты
  `test_wrong_embedded_policy_or_source_identity_refuses_publication`.
- `PauseActionsPublicationTests.test_retired_helper_is_allowed_only_as_exact_hash_legacy_metadata`:
  дублировал общий положительный сценарий legacy. Теперь
  `UpgradeManifestTests.test_retired_jar_is_exact_hash_metadata_not_an_active_download`
  отдельно проверяет все три настоящие миграции: EMI 26.2, World Menu 1.0.0 и
  Pause Actions 1.0.0, включая отсутствие в активных файлах и payload assets.

Сохранены проверки SHA и размеров, изменённых и лишних JAR, traversal и
active/legacy коллизий, детерминированного архива и его повреждения, обязательной
HTTPS-ссылки без credentials, сохранения авторских настроек и read-only verify,
точного контракта discovery, native Mixin, класса/загрузчика и JVM-параметров.
Отрицательные типовые проверки bool/int и будущей непроверенной платформы не
считаются устаревшей матрицей совместимости и остаются.

## Следующие обычные публикации

Workflow `.github/workflows/publish.yml` сохраняет прежние команды: native build
и probe Pause Actions, проверки управления, пять файлов unittest, генерацию и
`--verify-only`. Он запускается только от push в `main`. QA-only коммит аудита
отправляется с `[skip ci]`, поэтому этот конкретный push не запускает workflow;
обычные будущие push продолжают проходить все проверки. Исторические отчёты
предыдущих запусков сохраняются со своими датами и числами.
