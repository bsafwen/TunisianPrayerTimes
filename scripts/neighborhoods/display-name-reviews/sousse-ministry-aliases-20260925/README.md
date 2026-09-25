# Sousse Ministry search spellings

The dated Sousse identity review matched all 24 Ministry name exceptions to distinct INS codes and existing app sector IDs. This package installs the 18 spellings that are unique in the Sousse registry as additional Arabic search terms. It does not create picker rows, change parent/delegation assignments, or touch polygons or prayer-time references.

The unqualified `سهلول` spelling remains excluded because it denotes two different sectors under different parents. `El Gharbine` already displays as `الغربيين` through the existing runtime display-name asset, so the proposed display-title correction required no new row.

Run `python install.py` from the repository to apply the pinned asset update. The installer checks the current catalog and display-name hashes, checks IDs, source field expectations, and in-governorate alias uniqueness, then writes a backup and installation receipt. The source crosswalk and dated Ministry/INS pins are in the Sousse identity report under `work/official-imada-evidence-20260924-v2/01-sousse/identity-exceptions/` in the task workspace. The proposal JSON is copied here for review.
