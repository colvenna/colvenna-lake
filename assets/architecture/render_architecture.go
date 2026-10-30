// Render the static architecture SVG. The Mermaid and interactive diagrams
// describe the same system; this file preserves the hand-tuned static layout.
//
// Usage: go run assets/architecture/render_architecture.go [output.svg]
// PNG:   rsvg-convert -w 2880 output.svg -o assets/architecture/colvenna-lake-architecture.png
package main

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"strings"
)

const (
	width  = 1440
	height = 900
	font   = "Noto Sans CJK SC, PingFang SC, Microsoft YaHei, Segoe UI, Helvetica, Arial, sans-serif"
	navy   = "#0B3A4B"
	ink    = "#11556B"
	teal   = "#1BB59E"
	cream  = "#F4EFE4"
	tint   = "#F3FAF8"
	paper  = "#F8FBFC"
)

type point struct{ x, y float64 }
type canvas struct{ elements []string }

func number(v float64) string {
	if v == math.Trunc(v) {
		return strconv.FormatInt(int64(v), 10)
	}
	return strconv.FormatFloat(v, 'f', 1, 64)
}

func (c *canvas) text(x, y float64, value string, size int, weight, color, anchor string) {
	c.elements = append(c.elements, fmt.Sprintf(
		`<text x="%s" y="%s" text-anchor="%s" font-size="%d" font-weight="%s" fill="%s">%s</text>`,
		number(x), number(y), anchor, size, weight, color, value,
	))
}

func (c *canvas) nodeText(x, y float64, value string, size int, weight string) {
	c.elements = append(c.elements, fmt.Sprintf(
		`<text x="%.1f" y="%s" text-anchor="middle" font-size="%d" font-weight="%s" fill="#062533">%s</text>`,
		x, number(y), size, weight, value,
	))
}

func (c *canvas) rect(x, y, w, h float64, fill, stroke string, strokeWidth float64, radius int, dash string) {
	dashAttr := ""
	if dash != "" {
		dashAttr = fmt.Sprintf(` stroke-dasharray="%s"`, dash)
	}
	c.elements = append(c.elements, fmt.Sprintf(
		`<rect x="%s" y="%s" width="%s" height="%s" rx="%d" fill="%s" stroke="%s" stroke-width="%s"%s/>`,
		number(x), number(y), number(w), number(h), radius, fill, stroke, number(strokeWidth), dashAttr,
	))
}

func (c *canvas) node(x, y, w, h float64, title string, subs []string, fill, stroke, dash string) {
	c.rect(x, y, w, h, fill, stroke, 1.6, 8, dash)
	total := 14
	for range subs {
		total += 17
	}
	cy := y + h/2 - float64(total)/2
	c.nodeText(x+w/2, cy+14*0.85, title, 14, "bold")
	cy += 19
	for _, sub := range subs {
		c.nodeText(x+w/2, cy+12*0.85, sub, 12, "normal")
		cy += 17
	}
}

func (c *canvas) container(x, y, w, h float64, title, fill, stroke, dash string, endAnchor bool) {
	c.rect(x, y, w, h, fill, stroke, 1.5, 12, dash)
	tx, anchor := x+16, "start"
	if endAnchor {
		tx, anchor = x+w-16, "end"
	}
	c.text(tx, y+24, title, 15, "bold", navy, anchor)
}

func pathData(points []point) string {
	var d strings.Builder
	fmt.Fprintf(&d, "M%s,%s", number(points[0].x), number(points[0].y))
	for _, p := range points[1:] {
		fmt.Fprintf(&d, " L%s,%s", number(p.x), number(p.y))
	}
	return d.String()
}

func (c *canvas) label(x, y float64, value string) {
	tw := 0.0
	for _, ch := range value {
		if ch > 0x2E7F {
			tw += 12.5
		} else {
			tw += 6.3
		}
	}
	tw += 10
	left := x - tw/2
	c.elements = append(c.elements, fmt.Sprintf(
		`<rect x="%.1f" y="%s" width="%.1f" height="20" rx="4" fill="#FFFFFF" stroke="%s" stroke-width="1"/>`,
		left, number(y-10), tw, teal,
	))
	c.elements = append(c.elements, fmt.Sprintf(
		`<text x="%s" y="%s" text-anchor="middle" font-size="12.5" fill="%s">%s</text>`,
		number(x), number(y+4.5), navy, value,
	))
}

func (c *canvas) edge(points []point, dashed, emphasis bool, label string, labelAt point) {
	stroke, marker, dash := ink, "cl-arrow-ink", ""
	if dashed {
		stroke, marker = teal, "cl-arrow-teal"
		dash = ` stroke-dasharray="7 5"`
	}
	lineWidth := "1.7"
	if emphasis {
		lineWidth = "2.2"
	}
	c.elements = append(c.elements, fmt.Sprintf(
		`<path d="%s" fill="none" stroke="%s" stroke-width="%s"%s marker-end="url(#%s)"/>`,
		pathData(points), stroke, lineWidth, dash, marker,
	))
	if label != "" {
		c.label(labelAt.x, labelAt.y, label)
	}
}

func render() string {
	var c canvas

	// Containers
	c.container(215, 90, 1000, 755, "AWS 区域（目前只在 AWS 上验证）", paper, ink, "8 6", true)
	c.container(410, 132, 795, 118, "Web 入口与界面", tint, teal, "", false)
	c.container(410, 278, 220, 552, "EKS · 写入与维护", tint, teal, "", false)
	c.container(995, 432, 210, 300, "EKS · 查询与开放", tint, teal, "", false)

	// Users, sources and external readers
	c.node(720, 18, 200, 54, "浏览器用户", []string{"BI 用户 · 运维"}, cream, ink, "")
	c.node(40, 316, 150, 66, "colvenna CLI / 编排器", []string{"Airflow 等", "经 Kubernetes API"}, cream, ink, "")
	c.node(40, 452, 150, 86, "业务数据库", []string{"MySQL · PostgreSQL", "MongoDB", "binlog / WAL / oplog"}, cream, ink, "")
	c.node(40, 620, 150, 62, "日志 / 埋点采集", []string{"SDK · Agent"}, cream, ink, "")
	c.node(1260, 640, 140, 66, "外部 Iceberg 读端", []string{"Spark · Trino"}, cream, ink, "")

	// Web entry
	c.node(425, 166, 190, 72, "管理控制台", []string{"流水线全景 · 受控操作", "读 K8s API 与 Prometheus"}, "#FFFFFF", ink, "")
	c.node(720, 166, 200, 72, "公网 ALB + Cognito", []string{"TLS · 口令 + TOTP", "按组映射角色"}, "#FFFFFF", ink, "")
	c.node(1010, 166, 180, 72, "BI 查询界面", []string{"Apache Superset 6.1", "每人一个 Doris 用户"}, "#FFFFFF", ink, "")

	// Write and maintenance
	c.node(230, 620, 140, 62, "Kafka", []string{"MSK 或自建"}, "#FFFFFF", ink, "")
	c.node(425, 316, 190, 66, "colvenna-operator", []string{"把声明变成 Flink 作业", "FlinkDeployment · Job"}, "#FFFFFF", ink, "")
	c.node(425, 456, 190, 80, "接入作业 (Flink)", []string{"CDC action · 整库同步", "Kafka 事件流 (DataStream)"}, "#FFFFFF", ink, "")
	c.node(425, 616, 190, 72, "分层加工作业 (Flink)", []string{"ODS → DWD → DWS", "LakeTable · 常驻流作业"}, "#FFFFFF", ink, "")
	c.node(425, 724, 190, 80, "批任务 LakeTask (Flink)", []string{"Kubernetes Job · 按表排队", "压缩 · 过期 · 回刷 · 校验"}, "#FFFFFF", ink, "")

	// Storage, control plane, query and open layer
	c.node(720, 316, 200, 66, "colvenna-catalog", []string{"Paimon REST catalog", "PostgreSQL 后端"}, cream, navy, "")
	c.node(720, 446, 200, 118, "Amazon S3 · Paimon 表", []string{"ODS / DWD / DWS", "snapshot · manifest · data files"}, "#FFFFFF", ink, "")
	c.node(1010, 468, 180, 66, "查询引擎（存算一体）", []string{"基于 Apache Doris 4.1.4", "Apache Doris Operator"}, "#FFFFFF", ink, "")
	c.node(1010, 640, 180, 66, "Iceberg REST Catalog", []string{"Polaris · 可选"}, "#FFFFFF", ink, "6 4")

	// Web entry paths
	c.edge([]point{{820, 72}, {820, 166}}, false, true, "HTTPS", point{820, 118})
	c.edge([]point{{720, 202}, {615, 202}}, false, false, "登录后转发", point{667, 202})
	c.edge([]point{{920, 202}, {1010, 202}}, false, false, "登录后转发", point{965, 202})
	c.edge([]point{{590, 238}, {590, 316}}, true, false, "提交声明 · 读状态", point{590, 264})
	c.edge([]point{{1160, 238}, {1160, 468}}, false, false, "以本人身份查询", point{1160, 350})

	// Declarations and control plane
	c.edge([]point{{190, 349}, {425, 349}}, true, false, "提交声明 · 触发 LakeTask", point{307, 349})
	c.edge([]point{{615, 333}, {720, 333}}, true, false, "身份 · 授权", point{667, 333})
	c.edge([]point{{615, 470}, {650, 470}, {650, 360}, {720, 360}}, true, false, "元数据 · 提交 · 临时凭证", point{650, 420})

	// Data paths
	c.edge([]point{{190, 495}, {425, 495}}, false, true, "直读 binlog / WAL / oplog", point{307, 495})
	c.edge([]point{{190, 651}, {230, 651}}, false, false, "", point{})
	c.edge([]point{{370, 651}, {400, 651}, {400, 522}, {425, 522}}, false, false, "消费事件", point{400, 588})
	c.edge([]point{{615, 500}, {720, 500}}, false, true, "checkpoint 提交", point{667, 500})
	c.edge([]point{{615, 652}, {668, 652}, {668, 534}, {720, 534}}, false, false, "consumer-id 流读 · 写回", point{668, 593})
	c.edge([]point{{615, 764}, {820, 764}, {820, 564}}, true, false, "压缩 · 过期 · 回刷", point{820, 680})
	c.edge([]point{{1010, 512}, {920, 512}}, false, false, "直读", point{965, 512})
	c.edge([]point{{1010, 482}, {965, 482}, {965, 349}, {920, 349}}, true, false, "加载表 · 凭证", point{965, 410})
	c.edge([]point{{920, 540}, {965, 540}, {965, 673}, {1010, 673}}, true, false, "Iceberg 元数据", point{965, 600})
	c.edge([]point{{1260, 673}, {1190, 673}}, true, false, "只读", point{1237, 673})

	c.text(40, 875, "实线：数据与请求流　虚线：元数据、凭证、声明与维护操作　浅色底：区域外的用户、数据源与读端　colvenna-catalog：控制平面", 12, "normal", navy, "start")

	lines := []string{
		fmt.Sprintf(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d" font-family="%s" role="img" aria-label="Colvenna Lake 架构概览">`, width, height, width, height, font),
		"<defs>",
		fmt.Sprintf(`<marker id="cl-arrow-ink" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="%s"/></marker>`, ink),
		fmt.Sprintf(`<marker id="cl-arrow-teal" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="%s"/></marker>`, teal),
		"</defs>",
		fmt.Sprintf(`<rect width="%d" height="%d" fill="#FFFFFF"/>`, width, height),
	}
	lines = append(lines, c.elements...)
	lines = append(lines, "</svg>")
	return strings.Join(lines, "\n") + "\n"
}

func main() {
	path := "colvenna-lake-architecture.svg"
	if len(os.Args) > 1 {
		path = os.Args[1]
	}
	if err := os.WriteFile(path, []byte(render()), 0o644); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Println("wrote", path)
}
