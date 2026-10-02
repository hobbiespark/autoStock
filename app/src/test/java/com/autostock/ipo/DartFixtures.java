package com.autostock.ipo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 시험 자료 — OpenDART document.xml로 받은 실제 공시 원본(2026-10-02 수신, 공개 공시). {@code src/test/resources/dart/}.
 *
 * <ul>
 *   <li>20260909000307.zip — 네오사피엔스 [발행조건확정] (공모주, 확약 표 2개: 국내 22열 + 외국·합계 10열)</li>
 *   <li>20260909000276.zip — 한국제17호스팩 [발행조건확정] (스팩, 둘째 확약 표에 "구분" 열 없음)</li>
 *   <li>20260928000402.zip — 진코스텍 [발행조건확정] 두 번째 건 (합계 행 이름 "계")</li>
 *   <li>20260916000234-demand-forecast.xml — 브릴스 [발행조건확정]의 수요예측 결과 부분만(국내 16열, 언론 보도와 대조)</li>
 *   <li>20260914000188.zip — 툴젠 [발행조건확정] (상장사 유상증자 — 수요예측 없음)</li>
 *   <li>20261001000586.zip — 진코스텍 10/1 [발행조건확정] (기재사항 정정 — 수요예측 표 없음)</li>
 * </ul>
 */
final class DartFixtures {

    private DartFixtures() {
    }

    static byte[] bytes(String name) {
        try (InputStream in = DartFixtures.class.getResourceAsStream("/dart/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("시험 자료 없음: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** ZIP 안의 본문 XML, 또는 .xml 자료 그대로. */
    static String xml(String name) {
        byte[] raw = bytes(name);
        if (!name.endsWith(".zip")) {
            return new String(raw, StandardCharsets.UTF_8);
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().endsWith(".xml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new IllegalArgumentException("ZIP 안에 XML 없음: " + name);
    }
}
