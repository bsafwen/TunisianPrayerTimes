package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const (
	totalSurahs = 114
	maxRetries  = 5
	minFileSize = 10000 // 10KB minimum for a valid MP3
)

type reciterEntry struct {
	ID       int    `json:"id"`
	Name     string `json:"name"`
	Server   string `json:"server"`
	Surahs   int    `json:"surahs"`
	Priority int    `json:"priority"`
}

type recitersFile struct {
	Reciters []reciterEntry `json:"reciters"`
}

type downloadResult struct {
	surah   int
	success bool
	size    int64
	err     error
}

func folderName(name string) string {
	s := strings.ToLower(name)
	s = strings.ReplaceAll(s, " ", "_")
	s = strings.ReplaceAll(s, "-", "_")
	s = strings.ReplaceAll(s, "'", "")
	return s
}

func main() {
	maxReciters := flag.Int("max", 5, "Max reciters to download (by priority)")
	recitersPath := flag.String("reciters", "data/metadata/reciters.json", "Path to reciters.json")
	rawDir := flag.String("output", "data/raw", "Base output directory")
	flag.Parse()

	data, err := os.ReadFile(*recitersPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to read %s: %v\n", *recitersPath, err)
		os.Exit(1)
	}
	var rf recitersFile
	if err := json.Unmarshal(data, &rf); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to parse reciters JSON: %v\n", err)
		os.Exit(1)
	}

	sort.Slice(rf.Reciters, func(i, j int) bool {
		return rf.Reciters[i].Priority < rf.Reciters[j].Priority
	})

	reciters := rf.Reciters
	if *maxReciters > 0 && *maxReciters < len(reciters) {
		reciters = reciters[:*maxReciters]
	}

	client := &http.Client{
		Timeout: 30 * time.Minute,
		Transport: &http.Transport{
			MaxIdleConns:          10,
			IdleConnTimeout:       90 * time.Second,
			DisableCompression:    true,
			MaxConnsPerHost:       5,
			ResponseHeaderTimeout: 30 * time.Second,
		},
	}

	totalSucceeded, totalFailed := 0, 0

	for _, rec := range reciters {
		folder := folderName(rec.Name)
		outDir := filepath.Join(*rawDir, folder)
		server := strings.TrimRight(rec.Server, "/")

		fmt.Printf("\n%s\n  %s\n  Server: %s\n  Output: %s\n%s\n",
			strings.Repeat("=", 60), rec.Name, server, outDir, strings.Repeat("=", 60))

		if err := os.MkdirAll(outDir, 0755); err != nil {
			fmt.Fprintf(os.Stderr, "Failed to create %s: %v\n", outDir, err)
			continue
		}

		// Find missing surahs
		var missing []int
		for s := 1; s <= totalSurahs; s++ {
			path := filepath.Join(outDir, fmt.Sprintf("%03d.mp3", s))
			info, err := os.Stat(path)
			if err != nil || info.Size() < minFileSize {
				missing = append(missing, s)
			}
		}

		if len(missing) == 0 {
			fmt.Printf("  All %d surahs already downloaded!\n", totalSurahs)
			continue
		}

		fmt.Printf("  Downloading %d missing surahs...\n", len(missing))

		succeeded, failed := 0, 0
		for _, s := range missing {
			result := downloadSurah(client, s, server, outDir)
			if result.success {
				succeeded++
				fmt.Printf("  [OK]   %03d  (%s)\n", s, humanSize(result.size))
			} else {
				failed++
				fmt.Printf("  [FAIL] %03d  %v\n", s, result.err)
			}
		}

		fmt.Printf("  Done: %d succeeded, %d failed\n", succeeded, failed)
		totalSucceeded += succeeded
		totalFailed += failed
	}

	fmt.Printf("\n%s\nALL DONE: %d succeeded, %d failed across %d reciters\n%s\n",
		strings.Repeat("=", 60), totalSucceeded, totalFailed, len(reciters), strings.Repeat("=", 60))
}

func downloadSurah(client *http.Client, surah int, server, outDir string) downloadResult {
	filename := fmt.Sprintf("%03d.mp3", surah)
	destPath := filepath.Join(outDir, filename)
	url := fmt.Sprintf("%s/%s", server, filename)

	for attempt := 1; attempt <= maxRetries; attempt++ {
		if info, err := os.Stat(destPath); err == nil && info.Size() > minFileSize {
			return downloadResult{surah: surah, success: true, size: info.Size()}
		}

		err := downloadFile(client, url, destPath)
		if err != nil {
			fmt.Printf("    [%03d] attempt %d: %v\n", surah, attempt, err)
			time.Sleep(time.Duration(attempt) * 3 * time.Second)
			continue
		}

		info, err := os.Stat(destPath)
		if err == nil && info.Size() > minFileSize {
			return downloadResult{surah: surah, success: true, size: info.Size()}
		}
	}

	return downloadResult{surah: surah, success: false, err: fmt.Errorf("all %d retries exhausted", maxRetries)}
}

func downloadFile(client *http.Client, url, destPath string) error {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")

	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("GET failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("status %d", resp.StatusCode)
	}

	file, err := os.Create(destPath)
	if err != nil {
		return err
	}
	defer file.Close()

	written, err := io.Copy(file, resp.Body)
	if err != nil {
		return fmt.Errorf("read error after %s: %w", humanSize(written), err)
	}
	return nil
}

func humanSize(bytes int64) string {
	switch {
	case bytes >= 1024*1024*1024:
		return fmt.Sprintf("%.1f GB", float64(bytes)/(1024*1024*1024))
	case bytes >= 1024*1024:
		return fmt.Sprintf("%.1f MB", float64(bytes)/(1024*1024))
	case bytes >= 1024:
		return fmt.Sprintf("%.1f KB", float64(bytes)/1024)
	default:
		return fmt.Sprintf("%d B", bytes)
	}
}
