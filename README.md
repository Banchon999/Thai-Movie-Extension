# Cloudstream · ส่วนขยายภาษาไทย (25-HD, AnimeRuka)

**25-HD เวอร์ชัน 8: ดูได้ และดาวน์โหลดเก็บไว้ดูได้ — ทดสอบกับเว็บจริงแล้ว (5 ต.ค. 2026)**
**AnimeRuka เวอร์ชัน 1 (ทดลอง): ยังไม่ได้ทดสอบกับเว็บจริง — เว็บบล็อกเครือข่ายที่ใช้พัฒนา**

ซอร์สอยู่บน branch `main` และไฟล์ติดตั้งอยู่บน branch `builds` (repository เดียวมีทั้งสองส่วนขยาย)

## ดาวน์โหลด 25-HD เก็บไว้ดู

ในหน้าเรื่อง กดดาวน์โหลด (หรือ "ดาวน์โหลดจากมิเรอร์" เพื่อเลือกเอง) แล้วเลือกลิงก์
**ZMDB Server 1 • ดาวน์โหลด 1080p / 720p / 480p / 360p** — ไฟล์ที่ได้มีภาพ + เสียงทุกภาษาที่เว็บมี (เช่น ไทย/อังกฤษ)
และกรอไปมาได้ในตัวเล่นของแอป ถ้ากดดาวน์โหลดเฉย ๆ แอปจะเลือกความละเอียดสูงสุดเอง
(ตัวอย่าง Scream 7 ยาว 1 ชม. 49 นาที: 1080p ≈ 5 GB, 720p ≈ 2.2 GB, 360p ≈ 0.7 GB ตามอัตราบิตเฉลี่ยใน playlist) ซับไทย/อังกฤษดาวน์โหลดเป็นไฟล์ `.vtt` แยก
ตามภาษาที่ตั้งไว้ในการตั้งค่าซับไตเติลของแอป ลิงก์ "Auto" ใช้ดูออนไลน์ (ปรับความละเอียดอัตโนมัติ) แต่ดาวน์โหลดไม่ได้

เหตุผลทางเทคนิค: ตัวดาวน์โหลดของ Cloudstream รวมไฟล์จาก playlist เดียว แต่ ZMDB แยกภาพกับเสียงเป็น fMP4
และส่งไฟล์สื่อจาก CDN อื่น ส่วนขยายจึงรวมภาพ+เสียงเป็น MPEG-TS ทีละช่วง (ข้อมูลภาพ/เสียงเดิม ไม่เข้ารหัสใหม่)
ผ่านลิงก์ภายใน `zmdb-download.invalid` ที่ส่วนขยายตอบเองในแอป ลิงก์เก็บแค่รหัสวิดีโอ/ความละเอียด/ลำดับช่วง
จึงดาวน์โหลดต่อได้แม้ลิงก์เดิมหมดอายุ

## AnimeRuka (ทดลอง)

อนิเมะจาก `https://animeruka.com/` แยกตอนพากย์ไทย/ซับไทย (ปุ่ม Dub/Sub ของแอป) อ่านหน้าแรก ค้นหา เรื่องย่อ รายการตอน
และลิงก์จากตัวเล่น animemami (HLS) ตัวเล่นสำรองอื่นส่งให้ extractor ของ Cloudstream
เว็บนี้บล็อก IP นอกไทย/ศูนย์ข้อมูล จึงเขียนจากโครงสร้างที่ตัวดึงข้อมูลโอเพนซอร์สบันทึกไว้และทดสอบกับเว็บจำลอง
**ต้องทดสอบในแอปจริง** ถ้าใช้ไม่ได้ ให้ส่งภาพหน้าจอข้อความผิดพลาด หรือ HTML ของหน้าเรื่อง/หน้าตอนมา

## รายงานการเล่น 25-HD

หลังเล่นในแอปแล้วพบข้อผิดพลาด ให้กลับไปค้นหา **`25hd-debug`** โดยเลือกแหล่ง 25-HD
เปิดผล **25-HD v8 • รายงานการเล่น** แล้วดูเรื่องย่อ/กดขยาย: จะแสดงรหัส HTTP, โฮสต์
และประเภทเส้นทางของคำขอที่ล้มเหลว เช่น `HTTP 403 | playlist | example.org`
ถ้าต้องการรายงานใหม่ ให้ค้นหา `25hd-debug` อีกครั้ง ไม่เปิดจากประวัติเดิม
อย่าบังคับหยุดแอปก่อนอ่านรายงาน เพราะข้อมูลอยู่ในหน่วยความจำเท่านั้น

ตั้งแต่ v6 ใช้ `getVideoInterceptor` สังเกตคำขอที่ตัวเล่นส่งจริง; v7 เปลี่ยนเฉพาะโฮสต์ของคำขอสื่อที่ถูกปฏิเสธ
ไม่เปลี่ยน headers/status/body และไม่เก็บ token, path, query หรือ response body ในรายงาน
เก็บไม่เกิน 4 รอบเล่น รอบละ 8 ข้อผิดพลาดที่ไม่ซ้ำกัน; ประเภทไฟล์อนุมานจากเส้นทางเท่านั้น
ข้อจำกัด: hook นี้ทำให้ Cloudstream ใช้ OkHttp แทน Cronet จึงอาจทำให้พฤติกรรมเครือข่ายต่างจาก v5
ใช้กับตัวเล่นภายในแอป; Cast และ extractor ที่มี source เป็น provider อื่นอาจไม่ผ่าน hook นี้ (ดาวน์โหลดใช้ลิงก์ "ดาวน์โหลด" แทน)
รายงานว่างจึงไม่ได้แปลว่าเครือข่ายปกติ

## ติดตั้งใน Cloudstream

เปิด **Settings → Extensions → Add repository** แล้วใส่ลิงก์:

```text
https://raw.githubusercontent.com/Banchon999/Thai-Movie-Extension/builds/repo.json
```

ติดตั้ง **TwentyFiveHD** (25-HD) และ/หรือ **AnimeRuka** แล้วเลือกแหล่งในหน้าแรกของแอป
หรือดาวน์โหลด [TwentyFiveHD.cs3](https://raw.githubusercontent.com/Banchon999/Thai-Movie-Extension/builds/TwentyFiveHD.cs3)
/ [AnimeRuka.cs3](https://raw.githubusercontent.com/Banchon999/Thai-Movie-Extension/builds/AnimeRuka.cs3) โดยตรง

[สถานะบิลด์](https://github.com/Banchon999/Thai-Movie-Extension/actions) · [ผลตรวจสอบ](TEST_REPORT.md)

## สิ่งที่เขียนไว้

- หน้าอัปเดตล่าสุด พร้อมตามลิงก์หน้าถัดไปที่พบใน HTML
- ค้นหาภาษาไทยผ่านรูปแบบ WordPress `/?s=คำค้น`
- อ่านชื่อเรื่อง รูปปก เรื่องย่อ ปี และหมวดหมู่
- อ่านตอนซีรีส์จากรายการตอนและเรียงตามเลขตอน หากพบเลข
- อ่าน `<video>`, `<source>`, iframe ภายในตัวเล่น และลิงก์ MP4/HLS จาก player configuration
- รองรับข้อมูล DooPlay AJAX เมื่อมี `data-post`, `data-nume`, `data-type` อยู่ในหน้า
- ส่งลิงก์ embed ให้ `loadExtractor()` ของ Cloudstream และรองรับไฟล์คำบรรยายจาก `<track>`
- จำกัดจำนวนหน้าตัวเล่นและความลึก ป้องกัน iframe วนซ้ำ
- GitHub Actions สำหรับทดสอบ บิลด์ `.cs3`/`.jar` และเผยแพร่ `repo.json`

ตรวจ DOM จริงแล้ว: หน้าแรกใช้ `.movie_box` (พบ 54 การ์ด), ค้นหา รีชเชอร์ พบ 3 รายการ, ชื่อเรื่องอยู่ใน `.h1-text h1`, ตัวเล่นใช้ `data-original-src`
รุ่น 3 เลือกภาพจาก `.movie-description .thumb-img` ก่อน OpenGraph และเลือกขนาดจาก `srcset` พร้อมส่ง Referer ในคำขอภาพ
เพิ่มตัวอย่างภาพปกจริงของหนังและซีรีส์สำหรับ regression tests

รุ่น 4 อ่านตอนจาก `script#bootstrap` ใน iframe: `content.seasons[].episodes[]` และบันทึกเฉพาะ URL/ซีซั่น/ตอน
ตอนกดเล่นจะโหลด bootstrap ใหม่เพื่อรับโทเคน แล้วเรียก `/api/embed/links` ด้วย `query.id` (ไม่ใช่ `content.id`)
จากนั้นอ่าน ID ในลิงก์ stream037 เพื่อเรียก `/api/video/<id>` และส่ง `data.hlsUrl` เป็น HLS แม้ไม่มี `.m3u8`
หาก links API ตอบ 401 จะรับโทเคนใหม่และลองอีกครั้งเดียว; 403 จะแจ้งข้อผิดพลาด ไม่พยายามข้ามการบล็อก

**บิลด์และการทดสอบจำลองไม่ได้ยืนยันว่าเล่นจริงได้** ยังต้องทดสอบบนมือถือ โดยเฉพาะ playlist gateway, เสียงและซับไตเติล
ตัวเลือกเสียงที่แชร์ video ID จะใช้ master เดียวกัน ปลั๊กอินยังไม่บังคับเลือกภาษา/ซับตาม query ของ stream037

## ข้อจำกัดที่ต้องรู้

ตรวจผ่านเบราว์เซอร์เมื่อ 3 กันยายน 2026: เข้าถึงหน้าแรก หน้าค้นหา หน้าหนัง และหน้าซีรีส์ได้แล้ว
รุ่น 1 อ่านไม่พบรายการเพราะ selectors ไม่ตรงกับ `.movie_box` และอาจอ่านหัวเว็บแทนชื่อหนัง
รุ่น 2–3 แก้จาก DOM จริง พร้อมเก็บตัวอย่างใน `TwentyFiveHD/src/test/resources/25hd/`
ตัวเล่นที่พบคือ ZMDB ซึ่งแสดง Cloudflare security block ในเบราว์เซอร์ทดสอบ จึงหยุดตรวจที่ลิงก์ iframe
ผู้ใช้รายงานว่าค้นหาและเรื่องย่อทำงานแล้ว แต่หนังยังขึ้นไม่พบลิงก์และซีรีส์เลือกตอนผ่านปุ่มภายในตัวเล่น
รุ่น 4 ใช้ HTML bootstrap และ JSON ที่ผู้ใช้เก็บจากเครื่องที่เปิดได้; ยังไม่มีการยืนยันว่า ZMDB เล่นได้ใน Cloudstream
รุ่น 3 ป้องกัน extractor ที่เกิด exception ข้าม HTML fallback; รุ่น 4 เพิ่ม client สำหรับ ZMDB โดยใช้ข้อมูลที่ผู้ใช้ส่งมา
DooPlay เป็นเพียง fallback เดิม ไม่ใช่ตัวเล่นที่ยืนยันว่าเว็บนี้ใช้งาน

สภาพแวดล้อมนี้ไม่มี Gradle/Android SDK พร้อมใช้ และการดาวน์โหลดเครื่องมือบิลด์ไม่สำเร็จ
จึงใช้ GitHub Actions เป็นสภาพแวดล้อมบิลด์ ดู `TEST_REPORT.md` และหน้า Actions สำหรับผลล่าสุด

ตัวเล่นที่สร้าง URL ด้วย JavaScript, ต้องมี session เฉพาะ, ใช้ API/token เฉพาะเว็บ หรือไม่มี extractor ใน Cloudstream อาจยังเล่นไม่ได้
ระบบนี้ไม่แก้ CAPTCHA เอง และไม่มีการถอด DRM
บางเรื่องที่หน้าไพ่ไม่มีข้อมูลว่าเป็นซีรีส์อาจแสดงเป็นหนังในหน้าค้นหา ก่อนแก้ประเภทเมื่อเปิดรายละเอียด
ผลค้นหาปัจจุบันอ่านหน้าแรก ส่วนหน้าอัปเดตล่าสุดรองรับการโหลดหน้าถัดไป

## วิธีใช้ GitHub ให้บิลด์

1. เปิด repository https://github.com/Banchon999/Thai-Movie-Extension
2. หาก fork ไปใช้เอง ให้อนุญาต GitHub Actions ใน repo ของคุณ
3. เปิดแท็บ **Actions → Build 25-HD**
4. Workflow จะรันการทดสอบ Kotlin และ Python ก่อนบิลด์ หากขั้นตอนไหนล้มเหลวจะไม่เผยแพร่
5. เมื่อทั้ง `build` และ `publish` ผ่าน จะมี branch `builds` ที่มี `TwentyFiveHD.cs3`, `TwentyFiveHD.jar`, `plugins.json` และ `repo.json`
6. ใช้ **Settings → Extensions → Add repository** ใน Cloudstream ใส่ URL รูปแบบนี้ โดยเปลี่ยน OWNER และ REPO เป็นของจริง:

   ```text
   https://raw.githubusercontent.com/OWNER/REPO/builds/repo.json
   ```

7. เปิด repo ที่เพิ่ม แล้วติดตั้ง **TwentyFiveHD** และเลือกผู้ให้บริการ **25-HD (ทดลอง)**

ลิงก์สำหรับ repo นี้หลัง workflow เผยแพร่สำเร็จ:
`https://raw.githubusercontent.com/Banchon999/Thai-Movie-Extension/builds/repo.json`
Workflow ใช้ `GITHUB_REPOSITORY` สร้างลิงก์ให้ตรงกับ repo จริงโดยอัตโนมัติ ไม่ต้องแก้ Python
ไม่ต้องสร้าง branch `builds` ด้วยมือ และการอัปเดตไม่ใช้ force push
หาก repository มีข้อจำกัด Actions ให้เปิดใช้งาน Actions และอนุญาต job เผยแพร่เขียน contents

## บิลด์บนคอม

ต้องมี **JDK 17, Gradle 8.12, Android SDK platform 35 และ build-tools 35.0.0**
ตั้ง `ANDROID_HOME`/`ANDROID_SDK_ROOT` หรือใส่ `sdk.dir` ใน `local.properties`
ใช้ Gradle ที่ติดตั้งไว้โดยตรง ชุดนี้ไม่มี Gradle wrapper JAR

```bash
gradle --no-daemon TwentyFiveHD:testDebugUnitTest make makePluginsJson ensureJarCompatibility
python3 -m unittest discover -s tests -v
python3 scripts/stage_release.py --repository OWNER/REPO
```

ก่อนบิลด์ local ให้ตั้ง `GITHUB_REPOSITORY=OWNER/REPO` ด้วย เพื่อให้ metadata ตรงกับ repo ของคุณ
หากไม่ตั้ง โค้ดจะใช้ `Banchon999/Thai-Movie-Extension` เป็นค่าเริ่มต้นที่เสนอไว้
ไฟล์ปลั๊กอินจะอยู่ที่ `TwentyFiveHD/build/TwentyFiveHD.cs3` และชุดพร้อมเผยแพร่อยู่ที่ `dist/`
ถ้าใช้ Windows ให้ใช้ `python` แทน `python3` ได้

ใช้ dependency/API ตามแนวทางล่าสุดของ upstream ที่ตรวจพบ โดย `-SNAPSHOT` อาจเปลี่ยนภายหลัง
หากใช้ Cloudstream รุ่นเก่าที่ไม่รู้จัก `BasePlugin` ต้องอัปเดตแอปหรือปรับฐาน API ให้ตรงรุ่น

## ทดสอบกับเว็บไซต์จริงก่อนเปลี่ยนเป็น Stable

- หน้าแรก: ต้องมีรายการและรูปปก ไม่ใช่หน้าตรวจเบราว์เซอร์
- ค้นหาชื่อไทยที่มีอยู่จริง และชื่อที่ไม่มีจริง
- หนัง: ต้องเปิดรายละเอียดและเล่นได้อย่างน้อยหนึ่งเรื่อง
- ซีรีส์: ต้องเห็นรายการตอนและเล่นได้อย่างน้อยสองตอนติดกัน
- ทดลองพากย์ไทย/ซับไทยและเซิร์ฟเวอร์สำรองตามที่เว็บมี
- เปลี่ยนเครือข่ายแล้วลองเล่นใหม่ เพื่อเช็ก token/referer/session

เมื่อเล่นไม่ได้ ส่ง URL เรื่อง/ตอนที่เจอปัญหา ข้อความ error และเวอร์ชัน Cloudstream มา
หากยังเข้าถึงเว็บจากเครื่องพัฒนาไม่ได้ ให้แนบ HTML หน้าแรก หน้าค้นหา และหน้าตัวเล่นจากเบราว์เซอร์ที่เปิดได้
สามารถใช้ DevTools → Elements → คัดลอก outerHTML ของส่วนรายการ/ตัวเล่น โดยลบข้อมูลส่วนตัวก่อนส่ง
ไม่จำเป็นต้องส่งรหัสผ่านหรือ cookie

จุดแก้โครงสร้างเว็บอยู่ที่ `TwentyFiveHD/src/main/kotlin/com/demos/hd25/SiteParser.kt`
จุดแก้การเรียก API/ตัวเล่นอยู่ที่ `TwentyFiveHDProvider.kt`
เพิ่ม HTML จริงที่ย่อเหลือเฉพาะส่วนจำเป็นลงใน tests ก่อนเปลี่ยน selectors

## แหล่งอ้างอิงด้านเทคนิค

- https://github.com/recloudstream/extensions — โครงสร้าง Gradle, BasePlugin และการเผยแพร่
- https://github.com/recloudstream/cloudstream/blob/master/library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt
- https://github.com/recloudstream/cloudstream/blob/master/library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt
- https://github.com/recloudstream/gradle — รูปแบบ plugin metadata และไฟล์ CS3

อ้างอิง API จากต้นทางโดยตรง ไม่ได้รวมสื่อวิดีโอไว้ในโปรเจกต์
