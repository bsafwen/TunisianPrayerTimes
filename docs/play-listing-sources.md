# Play listing: sources and independence (plan A13, M9) — draft for the owner's review

Google Play asks an app that communicates government information to name its sources and to say it does not represent
any government. A13 (owner, 2026-10-11): keep the name «أوقات الصلاة تونس»; before the release that carries both
regions, the listing names each authority whose table a profile reproduces, with its official site, and states that
the app is independent. The names below are the app's own (MethodPresentation, approved with the M4 wording on
2026-10-16); the sites are the plan's (Appendix A). Approximate profiles (Sudan, Somalia, Djibouti, the Comoros' and
Mayotte's traditional tables, and the generic presets) reproduce no authority's table and are not listed.

## Independence line

- **العربية:** هذا التطبيق مستقلّ، ولا يمثّل أيّ حكومة أو وزارة أو هيئة رسمية. يعيد حساب المواقيت التي تنشرها الجهات المذكورة أدناه على مواقعها الرسمية، ويُنصح بالرجوع إليها عند أيّ اختلاف.
- **Français :** Cette application est indépendante et ne représente aucun gouvernement, ministère ni organisme officiel. Elle recalcule les horaires publiés par les autorités citées ci-dessous sur leurs sites officiels ; en cas de différence, ces sites font foi.
- **English:** This app is independent and does not represent any government, ministry or official body. It recomputes the times the authorities below publish on their official sites; where they differ, those sites prevail.

## Sources (one line each in the listing)

| Country or region | Authority (as the app names it) | Official site |
|---|---|---|
| Tunisia | المعهد الوطني للرصد الجوي | www.meteo.tn |
| Saudi Arabia | تقويم أم القرى (مدينة الملك عبد العزيز للعلوم والتقنية) | www.ummulqura.org.sa |
| Egypt | الهيئة المصرية العامة للمساحة | www.esa.gov.eg |
| Morocco | وزارة الأوقاف والشؤون الإسلامية | www.habous.gov.ma |
| Algeria | وزارة الشؤون الدينية والأوقاف | www.marw.gov.dz |
| Libya | الهيئة العامة للأوقاف والشؤون الإسلامية | awqaf.gov.ly |
| Mauritania | وزارة الشؤون الإسلامية والتعليم الأصلي | www.affairesislamiques.mr |
| Jordan | وزارة الأوقاف والشؤون والمقدسات الإسلامية | www.awqaf.gov.jo |
| Palestine | دار الإفتاء الفلسطينية؛ وزارة الأوقاف والشؤون الدينية (غزة) | www.darifta.ps; palwakf.ps |
| Lebanon | دار الفتوى | www.darelfatwa.gov.lb |
| Syria | وزارة الأوقاف | t.me/SyMOfE (the ministry's channel) |
| Iraq | ديوان الوقف السني | dws.gov.iq |
| Kuwait | وزارة الأوقاف والشؤون الإسلامية | www.awqaf.gov.kw |
| Qatar | وزارة الأوقاف والشؤون الإسلامية؛ دار التقويم القطري | www.awqaf.gov.qa; www.qatarch.com |
| Bahrain | المجلس الأعلى للشؤون الإسلامية | www.almajles.gov.bh |
| United Arab Emirates | الهيئة العامة للشؤون الإسلامية والأوقاف | www.awqaf.gov.ae |
| Oman | وزارة الأوقاف والشؤون الدينية | www.mara.gov.om |
| Yemen | الهيئة العامة للأوقاف والإرشاد | awqaf.gov.ye |
| Turkey, and Europe's Diyanet tables | رئاسة الشؤون الدينية التركية | namazvakitleri.diyanet.gov.tr |
| Austria | الجماعة الإسلامية في النمسا (IGGÖ) | www.derislam.at |
| Bosnia and Herzegovina, Montenegro, Serbia | المشيخة الإسلامية في البوسنة والهرسك | www.islamskazajednica.ba |
| Croatia | المشيخة الإسلامية في زغرب | www.islamska-zajednica.hr |
| Slovenia | المشيخة الإسلامية في سلوفينيا | www.vaktija.si |
| Kosovo | الجماعة الإسلامية في كوسوفو | bislame.net |
| Albania | الجماعة الإسلامية في ألبانيا | www.kmsh.al |
| North Macedonia | الجماعة الدينية الإسلامية في مقدونيا الشمالية | bfi.mk |
| Bulgaria | دار الإفتاء في بلغاريا | www.grandmufti.bg |
| Luxembourg | مجلس الشورى في لوكسمبورغ | www.shoura.lu |
| France (Rhône) | مجلس مساجد الرون | www.cmrofficiel.fr |
| France (APBIF) | جمعية أئمة فرنسا (APBIF) | www.apbif.fr |
| France (Paris) | مسجد باريس الكبير | www.grandemosqueedeparis.fr |
| Réunion | المجلس الإسلامي في لا ريونيون | www.islam-reunion.fr |
| Martinique | المركز الثقافي الإسلامي في مارتينيك | ccim.mq |
| French Guiana | جمعية مسلمي غويانا الفرنسية | a2cmgf.com |
| Norway | المجلس الإسلامي في النرويج | www.irn.no |
| Ireland | المؤسسة الإسلامية في أيرلندا | islamicfoundation.ie |
| Russia | الإدارة الدينية لمسلمي روسيا؛ الإدارة الدينية لمسلمي تتارستان | dumrf.ru; dumrt.ru |

Place names: GeoNames (CC BY 4.0) and Wikidata (CC0), credited in the app (`assets/places/PLACES-LICENSE.txt`).

## Privacy policy review (M9)

`docs/privacy-policy.html` (updated 2026-10-16) now says what the App keeps abroad: a position rounded to about 1 km
or a picked catalogue city, its time zone, the method and Friday time per country, all on the device only; that the
time-zone change check asks for the location only with automatic location on, without the background-location
permission, and otherwise posts a notification to choose the city (on the emulator, Android 17 refused that read with
no screen of the app open, 2026-10-16, 9.1);
that prayer times abroad are computed on the device from bundled tables and place names (GeoNames, Wikidata); and
that analytics carry only whether the place is a Tunisian municipality or a place abroad (`place_kind`).

The plan asks this review to check the EU's rules, since the release reaches EU countries (Q28): the policy relies on
Firebase Analytics, which stores an app-instance identifier on the device. Under the EU's ePrivacy rules that kind of
storage for analytics usually needs the user's consent, which the App does not ask today. This is not legal advice;
the choices are:

1. Turn Firebase Analytics collection off when the phone's network or SIM country is in the EEA, the UK or Switzerland
   (`FirebaseAnalytics.setAnalyticsCollectionEnabled(false)`), at the cost of no analytics from many Tunisians abroad.
2. Ask for consent with a switch in settings, off by default in those countries (a new Arabic string to approve).
3. Take legal advice and keep the current behaviour if it confirms a lawful basis.

Applied on 2026-10-16, following the owner's rule to do nothing illegal: option 1. The manifest turns collection off
by default, and `AnalyticsTracker.applyRegionalCollection` turns it on at start and after each place change only when
none of the SIM country, the network country and the place is in the EEA, the UK, Switzerland or French overseas.
The policy says so. Switching to option 2 or 3 later is a small change.

## For the owner to settle before publishing

- Check each site still answers and is the authority's own (a ministry may move its site; Syria's is its channel).
- The Austrian row names IGGÖ, whose nine city tables the app reproduces; its Arabic name is new and not yet in the app.
- Keep the list in step with the profiles: a profile added or dropped changes a line.
