#!/usr/bin/env python3

import itertools
import json
import re
import sys
import tarfile
from argparse import (
    ArgumentParser,
)
from concurrent.futures import ProcessPoolExecutor, as_completed
from logging import (
    DEBUG,
    INFO,
    Formatter,
    Logger,
    StreamHandler,
)
from pathlib import Path
from urllib import request
from xml.etree import (
    ElementTree as etree
)
from urllib.error import (
    URLError,
)
from typing import (
    Callable,
    Dict,
)


# turn on/off diagnostic information
GOOGLE1000_PATH = './data/google1000'
GOOGLE1000_URL = 'https://ocrhl.jbaiter.de/data/google1000_texts.tar.gz'
GOOGLE1000_NUM_VOLUMES = 1000
GOOGLE1000_BATCH_SIZE = 4
LUNION_PATH = './data/bnl_lunion'
LUNION_TEXTS_URL = 'https://ocrhl.jbaiter.de/data/bnl_lunion_texts.tar.gz'
LUNION_NUM_ARTICLES = 41446
LUNION_BATCH_SIZE = 1000
SOLR_HOST = 'localhost:8983'
HOCR_METADATA_PAT = re.compile(
    r'<meta name=[\'"]DC\.(?P<key>.+?)[\'"] content=[\'"](?P<value>.+?)[\'"]\s*/?>')
NSMAP = {
    'mets': 'http://www.loc.gov/METS/',
    'mods': 'http://www.loc.gov/mods/v3'
}
DEFAULT_N_WORKERS = 2
DEFAULT_LOG_LEVEL = INFO
LOGGER_NAME = 'ingest'
LOGGER = None


class SolrException(Exception):
    def __init__(self, resp, payload):
        self.message = resp
        self.payload = payload


def main_ingest(the_args):
    _n_worker = the_args.num_workers
    _is_books_only = the_args.books_only
    _book_base_path = Path(GOOGLE1000_PATH).absolute()
    _book_base_path.mkdir(parents=True, exist_ok=True)
    LOGGER.info("Load and Indexing Google %s Books into %s",
                GOOGLE1000_NUM_VOLUMES, _book_base_path)
    
    futs = []
    with ProcessPoolExecutor(max_workers=_n_worker) as pool_exec:
        gbooks_iter = load_documents(
            GOOGLE1000_URL, _book_base_path, transform_gbook_to_document)
        for idx, batch in enumerate(_generate_batches(gbooks_iter, GOOGLE1000_BATCH_SIZE)):
            futs.append(pool_exec.submit(_index_documents, batch))
            LOGGER.info("process %04d/%d", 
                         ((idx+1)*GOOGLE1000_BATCH_SIZE), GOOGLE1000_NUM_VOLUMES)
        for fut in as_completed(futs):
            fut.result()
    if _is_books_only:
        LOGGER.info("Only GBooks requested, job done.")
        return
    
    _lunion_bas_path = Path(LUNION_PATH).absolute()
    _lunion_bas_path.mkdir(parents=True, exist_ok=True)
    LOGGER.info("Load and Indexing BNL/L'Union articles into %s",
                _lunion_bas_path)

    futs = []
    with ProcessPoolExecutor(max_workers=_n_worker) as pool_exec:
        bnl_iter = bnl_load_documents(_lunion_bas_path)
        for idx, batch in enumerate(_generate_batches(bnl_iter, LUNION_BATCH_SIZE)):
            futs.append(pool_exec.submit(_index_documents, batch))
            LOGGER.info("process %05d/%d",
                         (idx+1)*LUNION_BATCH_SIZE, LUNION_NUM_ARTICLES)
        for fut in as_completed(futs):
            fut.result()
    LOGGER.info("All Jobs done.")


def gbooks_parse_metadata(hocr):
    # I know, the <center> won't hold, but I think it's okay in this case,
    # especially since we 100% know what data this script is going to work with
    # and we don't want an external lxml dependency in here
    raw_meta = {key: int(value) if value.isdigit() else value
                for key, value in HOCR_METADATA_PAT.findall(hocr)}
    return {
        'author': [raw_meta.get('creator')] if 'creator' in raw_meta else [],
        'title': [raw_meta['title']],
        'date': f"{raw_meta['date']}-01-01T00:00:00Z",
        **{k: v for k, v in raw_meta.items()
           if k not in ('creator', 'title', 'date')}
    }


def transform_gbook_to_document(document_path: Path) -> Dict:
    _content = document_path.read_text()
    _doc_id = document_path.stem.split("_")[1]
    _doc_name = document_path.name
    return {'id': _doc_id,
            'source': 'gbooks',
            'ocr_text': f'/data/google1000/{_doc_name}',
            **gbooks_parse_metadata(_content)}


def _gbook_doc_path_from_tar_entry(ti, base_path:Path) -> Path:
    if not ti.name.endswith('.hocr'):
        return None
    vol_id = ti.name.split('/')[-1].split('.')[0]
    return base_path / f'{vol_id}.hocr'    


def load_documents(the_url, base_path: Path, transform_func: Callable):
    try:
        with request.urlopen(the_url) as resp:
            try:
                tf = tarfile.open(fileobj=resp, mode='r|gz')
                for ti in tf:
                    _doc_path = _gbook_doc_path_from_tar_entry(ti, base_path)
                    if _doc_path is None:
                        continue
                    if not _doc_path.exists():
                        LOGGER.debug("Download %s", _doc_path)
                        try:
                            _local_file = tf.extractfile(ti).read()
                            with _doc_path.open('wb') as fp:
                                fp.write(_local_file)
                        except tarfile.ReadError as _entry_read_error:
                            LOGGER.error("Fail process %s: %s", 
                                ti, _entry_read_error.args[0])
                            continue
                    LOGGER.debug("Extract metadata from %s", _doc_path)
                    yield transform_func(_doc_path)
            except tarfile.ReadError as _tar_read_error:
                LOGGER.error("Processing %s: %s", 
                             tf, _tar_read_error.args[0])
    except URLError as _exc:
        LOGGER.error("Fail request %s: %s", the_url, _exc.args[0])


def bnl_load_documents(base_path: Path):
    if bnl_are_volumes_missing(base_path):
        LOGGER.info("Download missing BNL/L'Union issues to %s", base_path)
        with request.urlopen(LUNION_TEXTS_URL) as resp:
            try:
                tf = tarfile.open(fileobj=resp, mode='r|gz')
                last_vol = None
                for ti in tf:
                    sanitized_name = re.sub(r'^\./?', '', ti.name)
                    if not sanitized_name:
                        continue
                    if ti.isdir() and '/' not in sanitized_name:
                        if last_vol is not None:
                            doc_path = base_path / last_vol
                            mets_path = next(iter(doc_path.glob("*-mets.xml")))
                            doc_id = last_vol.replace("newspaper_lunion_", "")
                            yield from bnl_extract_article_docs(
                                doc_id, mets_path, doc_path / 'text')
                        last_vol = sanitized_name
                    if ti.isdir():
                        (base_path / ti.name).mkdir(parents=True, exist_ok=True)
                    else:
                        out_path = base_path / ti.name
                        with out_path.open('wb') as fp:
                            fp.write(tf.extractfile(ti).read())
            except tarfile.ReadError as _tar_read_error:
                LOGGER.error("ERROR processing %s: %s", 
                             tf, _tar_read_error.args[0])
            doc_path = base_path / last_vol
            mets_path = next(iter(doc_path.glob("*-mets.xml")))
            doc_id = last_vol.replace("newspaper_lunion_", "")
            yield from bnl_extract_article_docs(
                doc_id, mets_path, doc_path / 'text')
    else:
        with ProcessPoolExecutor(max_workers=4) as pool:
            futs = []
            for issue_dir in base_path.iterdir():
                if not issue_dir.is_dir() or not issue_dir.name.startswith('15'):
                    continue
                mets_path = next(iter(issue_dir.glob("*-mets.xml")))
                doc_id = issue_dir.name.replace("newspaper_lunion_", "")
                futs.append(pool.submit(bnl_extract_article_docs, doc_id, mets_path, issue_dir / 'text'))
            for fut in as_completed(futs):
                yield from fut.result()


def bnl_get_metadata(mods_tree):
    authors = []
    name_elems = mods_tree.findall('.//mods:name', namespaces=NSMAP)
    for name_elem in name_elems:
        role = name_elem.findtext('.//mods:roleTerm', namespaces=NSMAP)
        if role == 'aut':
            authors.append(" ".join(e.text for e in 
                name_elem.findall('.//mods:namePart', namespaces=NSMAP)))
    return {
        'author': authors,
        'title': [e.text for e in mods_tree.findall(".//mods:title",
                                                    namespaces=NSMAP)],
        'subtitle': [e.text for e in mods_tree.findall(".//mods:subTitle",
                                                       namespaces=NSMAP)]
    }


def bnl_get_article_pointer(path_regions):
    grouped = {
        p: sorted(bid for bid, _ in bids)
        for p, bids in itertools.groupby(path_regions, key=lambda x: x[1])}
    pointer_parts = []
    for page_path, block_ids in grouped.items():
        local_path = Path(LUNION_PATH) / page_path
        regions = []
        with local_path.open('rb') as fp:
            page_bytes = fp.read()
        for block_id in block_ids:
            start_match = re.search(
                rb'<([A-Za-z]+?) ID="%b"' % (block_id.encode()), page_bytes)
            start = start_match.start()
            end_tag = b'</%b>' % (start_match.group(1),)
            end = page_bytes.index(end_tag, start) + len(end_tag)
            regions.append((start, end))
        pointer_parts.append(
            '/data/bnl_lunion/{}[{}]'.format(
                page_path,
                ','.join('{}:{}'.format(*r) for r in sorted(regions))))
    return '+'.join(pointer_parts)


def bnl_extract_article_docs(issue_id, mets_path, alto_basedir):
    LOGGER.debug("bnl_extract_article_docs %s to %s",
                 issue_id, mets_path)
    mets_tree = etree.parse(str(mets_path))
    article_elems = mets_tree.findall(
        ".//mets:structMap[@TYPE='LOGICAL']//mets:div[@TYPE='ARTICLE']",
        namespaces=NSMAP)
    title_info = mets_tree.find(
        ".//mets:dmdSec[@ID='MODSMD_PRINT']//mods:titleInfo",
        namespaces=NSMAP)
    newspaper_title = title_info.findtext('./mods:title', namespaces=NSMAP)
    newspaper_part = title_info.findtext('./mods:partNumber', namespaces=NSMAP)
    file_mapping = {
        e.attrib['ID']: next(iter(e)).attrib['{http://www.w3.org/1999/xlink}href'][9:]
        for e in mets_tree.findall('.//mets:fileGrp[@USE="Text"]/mets:file',
                                   namespaces=NSMAP)}
    out = []
    for elem in article_elems:
        meta_id = elem.attrib['DMDID']
        path_regions = [
            (e.attrib['BEGIN'],
             alto_basedir.parent.name + '/' + file_mapping.get(e.attrib['FILEID']))
            for e in elem.findall('.//mets:fptr//mets:area',
                                  namespaces=NSMAP)]
        mods_meta = mets_tree.find(
            './/mets:dmdSec[@ID="{}"]//mods:mods'.format(meta_id),
            namespaces=NSMAP)
        issue_date = mets_tree.findtext('.//mods:dateIssued', namespaces=NSMAP)
        article_no = meta_id.replace("MODSMD_ARTICLE", "")
        out.append({
            'id': '{}-{}'.format(issue_id, article_no),
            'source': 'bnl_lunion',
            'issue_id': issue_id,
            'date': issue_date + 'T00:00:00Z',
            'newspaper_title': newspaper_title,
            'newspaper_part': newspaper_part,
            'ocr_text': bnl_get_article_pointer(path_regions),
            **bnl_get_metadata(mods_meta),
        })
    return out


def bnl_are_volumes_missing(base_path):
    num_pages = sum(1 for _ in base_path.glob("*/text/*.xml"))
    return num_pages != 10880


def _index_documents(docs):
    _req_url = f"http://{SOLR_HOST}/solr/ocr/update?softCommit=true"
    try:
        LOGGER.debug("Push %d documents to %s", len(docs), _req_url)
        req = request.Request(_req_url,
                              data=json.dumps(docs).encode('utf8'),
                              headers={'Content-Type': 'application/json'})
        resp = request.urlopen(req)
        if resp.status >= 400:
            raise SolrException(json.loads(resp.read()), docs)
    except URLError as _url_err:
        LOGGER.error("Fail indexing %d documents: %s", len(docs), _url_err)


def _generate_batches(it, chunk_size):
    cur_batch = []
    for x in it:
        cur_batch.append(x)
        if len(cur_batch) == chunk_size:
            yield cur_batch
            cur_batch = []
    if cur_batch:
        yield cur_batch


def _calculate_log_level(the_level) -> int:
    if isinstance(the_level, str):
        _level_str = str(the_level).lower()
        if 'debug' in _level_str:
            return DEBUG
    return DEFAULT_LOG_LEVEL


if __name__ == '__main__':
    PARSER = ArgumentParser(description='ingest example data into SOLR')
    PARSER.add_argument('--log-level', help='like "debug", "info", "error" (default:info)', required=False,
                        default=DEFAULT_LOG_LEVEL)
    PARSER.add_argument('--num-workers', help="how many concurrent processes to start (default:4)", required=False,
                        default=DEFAULT_N_WORKERS, type=int)
    PARSER.add_argument('--books-only', help="if only interested in book corpus (default: False)", required=False, action='store_true')
    ARGS = PARSER.parse_args()
    LOGGER = Logger(LOGGER_NAME, _calculate_log_level(ARGS.log_level))
    STDOUT_HANDLER = StreamHandler(sys.stdout)
    STDOUT_HANDLER.setFormatter(Formatter("%(asctime)s [%(levelname)s] %(message)s", datefmt="%Y-%m-%dT%H:%M:%S"))
    LOGGER.addHandler(STDOUT_HANDLER)
    main_ingest(ARGS)
