package com.fsixel.deruyrpg.dungeon;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 판 기록 (운영·밸런스용). plugins/DeruyRPG/runs.csv 에 한 판 = 한 줄.
 * 열: 날짜, 챕터, 결과, 인원, 총 초, 보스 초, 미니보스 초, 사망 수, 경로(라운드 id &gt; ...), 참가자
 * /dg stats 로 최근 판 요약 (클리어율·평균 시간·보스 시간 — 목표: 챕터 15분, 보스 6분).
 */
final class RunLog {
    private static final String HEADER = "date,chapter,result,players,total_s,boss_s,miniboss_s,deaths,path,members";
    private final File file;

    RunLog(File dataFolder) {
        this.file = new File(dataFolder, "runs.csv");
    }

    void write(Run run, String result, List<String> names) {
        long now = System.currentTimeMillis();
        long total = (now - run.startedAt) / 1000;
        String line = String.join(",",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                run.chapter.id(), result, String.valueOf(run.startSize), String.valueOf(total),
                String.valueOf(run.roundSeconds.getOrDefault("BOSS", 0L)),
                String.valueOf(run.roundSeconds.getOrDefault("MINIBOSS", 0L)),
                String.valueOf(run.deaths), String.join(">", run.path), String.join(" ", names));
        try {
            boolean fresh = !file.exists();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                if (fresh) w.write(HEADER + "\n");
                w.write(line + "\n");
            }
        } catch (IOException ignored) {
        }
    }

    /** 최근 n판 요약 문장들. */
    List<String> summary(int n) {
        List<String> out = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        try {
            if (file.exists()) {
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                for (int i = Math.max(1, lines.size() - n); i < lines.size(); i++) {
                    String[] c = lines.get(i).split(",", -1);
                    if (c.length >= 9) rows.add(c);
                }
            }
        } catch (IOException ignored) {
        }
        if (rows.isEmpty()) {
            out.add("기록 없음 (plugins/DeruyRPG/runs.csv)");
            return out;
        }
        int clear = 0, deaths = 0, bossN = 0;
        long total = 0, boss = 0, clearTotal = 0;
        Map<String, Integer> firstPick = new TreeMap<>();
        for (String[] c : rows) {
            boolean ok = c[2].equals("clear");
            if (ok) {
                clear++;
                clearTotal += parse(c[4]);
            }
            total += parse(c[4]);
            long b = parse(c[5]);
            if (b > 0) {
                boss += b;
                bossN++;
            }
            deaths += (int) parse(c[7]);
            String[] path = c[8].split(">");
            if (path.length > 1) firstPick.merge(path[1], 1, Integer::sum);
        }
        int k = rows.size();
        out.add("최근 " + k + "판 · 클리어 " + clear + " (" + (clear * 100 / k) + "%) · 평균 사망 " + String.format("%.1f", deaths / (double) k));
        out.add("평균 시간 " + mmss(total / k) + (clear > 0 ? " · 클리어 평균 " + mmss(clearTotal / clear) : "") + "  (목표 15:00)");
        if (bossN > 0) out.add("보스전 평균 " + mmss(boss / bossN) + " (" + bossN + "판, 목표 6:00)");
        out.add("첫 갈림길 선택: " + firstPick);
        return out;
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String mmss(long s) {
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }
}
