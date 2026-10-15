"""Import Seoul's public station IDs and curated same-line connections.

Usage: python3 scripts/import-seoul-subway.py /path/to/실시간도착_역정보.xlsx
Source: Seoul Open Data Plaza OA-12764, attachment 57 (2026-09-02).
Only a local workbook is read; no personal API key is needed or stored.
"""
import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

NS = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('workbook', type=Path)
parser.add_argument('--date', help='Published station data date, YYYY-MM-DD; otherwise read YYYYMMDD from the filename')
args = parser.parse_args()
match = re.search(r'\d{8}', args.workbook.stem)
if args.date:
    station_date = datetime.strptime(args.date, '%Y-%m-%d').date().isoformat()
elif match:
    station_date = datetime.strptime(match.group(), '%Y%m%d').date().isoformat()
else:
    parser.error('Specify the source publication date with --date YYYY-MM-DD')
with zipfile.ZipFile(args.workbook) as workbook:
    strings = [''.join(item.itertext()) for item in ET.fromstring(workbook.read('xl/sharedStrings.xml')).findall('m:si', NS)]
    rows = []
    for row in ET.fromstring(workbook.read('xl/worksheets/sheet1.xml')).findall('.//m:row', NS)[1:]:
        cells = {}
        for cell in row:
            value = cell.find('m:v', NS)
            raw = value.text if value is not None else ''
            cells[''.join(c for c in cell.get('r') if c.isalpha())] = strings[int(raw)] if cell.get('t') == 's' else raw
        if cells.get('A') and cells.get('B'):
            rows.append([cells.get(key, '') for key in 'ABCD'])

lines = []
for line_id in dict.fromkeys(row[0] for row in rows):
    items = [row for row in rows if row[0] == line_id]
    ids = [row[1] for row in items]
    names = {row[2]: row[1] for row in items}
    def station(name):
        return names[name]
    def path(*names):
        return [station(name) for name in names]
    segments = [ids]
    ring = []
    if line_id == '1001':
        segments = [path('연천', '전곡', '청산') + [i for i in ids if i.startswith('1001000')],
                    [station('구로')] + [i for i in ids if i.startswith('1001080')],
                    path('금천구청', '광명'), path('병점', '서동탄')]
    elif line_id == '1002':
        ring = [i for i in ids if i.startswith('1002000')]
        segments = [[station('성수')] + [i for i in ids if i.startswith('10020021')],
                    [station('신도림')] + [i for i in ids if i.startswith('10020023') and i not in ring]]
    elif line_id == '1005':
        segments = [[i for i in ids if i.startswith('1005000')], [station('강동')] + [i for i in ids if i.startswith('1005080')]]
    elif line_id == '1006':
        ring = ids[:6]
        segments = [[ids[0]] + ids[6:]]
    elif line_id == '1032':
        # Separate operating sections; never invent a Seoul–Suseo connection.
        segments = [ids[:5], ids[5:]]
    elif line_id == '1063':
        west = [i for i in ids if i.startswith('10630753')]
        east = [i for i in ids if i.startswith('10630751')]
        segments = [list(reversed(west)) + path('효창공원앞') + east,
                    path('가좌', '신촌(경의중앙선)', '서울')]
    elif line_id == '1065':
        segments = [path('서울', '공덕', '홍대입구', '디지털미디어시티', '마곡나루', '김포공항', '계양', '검암', '청라국제도시', '영종', '운서', '공항화물청사', '인천공항1터미널', '인천공항2터미널')]
    elif line_id == '1067':
        segments = [[i for i in ids if i != station('광운대')], path('광운대', '상봉')]
    entry = {'id': line_id, 'name': items[0][3], 'stations': [[row[1], row[2]] for row in items], 'segments': segments, 'ring': ring}
    lines.append(entry)

output = {'version': 1, 'stationDataDate': station_date,
          'source': 'https://data.seoul.go.kr/dataList/OA-12764/A/1/datasetView.do',
          'license': '서울특별시, 공공누리 제1유형 (출처표시)', 'lines': lines}
path = Path(__file__).resolve().parents[1] / 'android/app/src/main/resources/seoul-subway.json'
path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + '\n')
print(f'{len(lines)} lines, {sum(len(line["stations"]) for line in lines)} station records: {path}')
