"""Exercise production read-state and recycle-bin queries without an Android device."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DAO = ROOT / 'app/src/main/kotlin/org/fossify/messages/interfaces'

class ConversationFeedbackTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.executescript('CREATE TABLE conversations(thread_id INTEGER, read INTEGER, unread_count INTEGER); CREATE TABLE messages(id INTEGER PRIMARY KEY, thread_id INTEGER); CREATE TABLE recycle_bin_messages(id INTEGER PRIMARY KEY);')
        self.db.executemany('INSERT INTO conversations VALUES(?,?,?)', [(1, 0, 3), (2, 0, 4)])
        self.db.executemany('INSERT INTO messages VALUES(?,?)', [(10, 1), (11, 1), (20, 2), (21, 2)])
        self.db.executemany('INSERT INTO recycle_bin_messages VALUES(?)', [(10,), (11,), (20,)])

    def hidden(self):
        sql = re.search(r'@Query\("([^"]+)"\)\s+fun getFullyRecycledThreadIds', (DAO / 'MessagesDao.kt').read_text()).group(1)
        return {row[0] for row in self.db.execute(sql)}

    def test_mark_read_clears_count_only_for_selected_thread(self):
        sql = re.search(r'@Query\("([^"]+)"\)\s+fun markRead', (DAO / 'ConversationsDao.kt').read_text()).group(1)
        self.db.execute(sql, {'threadId': 1})
        self.assertEqual([(1, 0), (0, 4)], self.db.execute('SELECT read,unread_count FROM conversations ORDER BY thread_id').fetchall())

    def test_partial_recycle_keeps_thread_visible(self):
        self.assertEqual({1}, self.hidden())

    def test_new_sms_restores_thread_visibility(self):
        self.db.execute('INSERT INTO messages VALUES(12,1)')
        self.assertEqual(set(), self.hidden())

    def test_restore_from_recycle_keeps_thread_visible(self):
        self.db.execute('DELETE FROM recycle_bin_messages WHERE id=10')
        self.assertEqual(set(), self.hidden())

class IncomingConversationVisibilityTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.executescript('''
            CREATE TABLE conversations(thread_id INTEGER PRIMARY KEY, date INTEGER,
                snippet TEXT, is_scheduled INTEGER);
            CREATE TABLE messages(id INTEGER PRIMARY KEY, thread_id INTEGER, date INTEGER,
                body TEXT, is_scheduled INTEGER);
            CREATE TABLE recycle_bin_messages(id INTEGER PRIMARY KEY);
        ''')
        self.sql = re.search(r'@Query\("([^"]+)"\)\s+fun getAllRegularWithLatestSnippet',
                             (DAO / 'ConversationsDao.kt').read_text()).group(1)

    def visible(self):
        return self.db.execute(self.sql).fetchall()

    def test_message_without_conversation_is_invisible_until_metadata_repaired(self):
        self.db.execute("INSERT INTO messages VALUES(123,9,100,'incoming',0)")
        self.assertEqual([], self.visible())
        self.db.execute("INSERT INTO conversations VALUES(9,100,'incoming',0)")
        self.assertEqual('incoming', self.visible()[0][0])
        self.assertEqual(9, self.visible()[0][2])

    def test_repeated_local_upsert_does_not_duplicate_message_or_thread(self):
        for _ in range(3):
            self.db.execute("INSERT OR REPLACE INTO messages VALUES(123,9,100,'incoming',0)")
            self.db.execute("INSERT OR REPLACE INTO conversations VALUES(9,100,'incoming',0)")
        self.assertEqual(1, len(self.visible()))
        self.assertEqual(1, self.db.execute('SELECT COUNT(*) FROM messages').fetchone()[0])

    def test_new_sms_keeps_partly_recycled_thread_visible_with_latest_snippet(self):
        self.db.execute("INSERT INTO conversations VALUES(9,50,'old',0)")
        self.db.execute("INSERT INTO messages VALUES(122,9,50,'old',0)")
        self.db.execute('INSERT INTO recycle_bin_messages VALUES(122)')
        self.assertEqual([], self.visible())
        self.db.execute("INSERT INTO messages VALUES(123,9,100,'incoming',0)")
        self.assertEqual(('incoming', 100), self.visible()[0][:2])

class IncomingSummaryQueriesTest(unittest.TestCase):
    def test_production_queries_exclude_recycled_and_count_only_unread_inbox(self):
        import re
        from pathlib import Path
        source=(Path(__file__).resolve().parents[1]/'app/src/main/kotlin/org/fossify/messages/interfaces/MessagesDao.kt').read_text()
        queries=re.findall(r'@Query\("""(.*?)"""\)\s*fun (getLatestNonRecycledMessage|countUnreadNonRecycledInboxMessages)',source,re.S)
        self.assertEqual(len(queries),2)
        with sqlite3.connect(':memory:') as db:
            db.executescript('CREATE TABLE messages(id INTEGER PRIMARY KEY,thread_id INTEGER,date INTEGER,read INTEGER,type INTEGER);CREATE TABLE recycle_bin_messages(id INTEGER PRIMARY KEY);')
            db.executemany('INSERT INTO messages VALUES(?,?,?,?,?)',[(1,4,100,0,1),(2,4,200,1,1),(3,4,200,0,1),(4,4,300,0,1),(5,9,999,0,1),(6,4,150,0,2)])
            db.execute('INSERT INTO recycle_bin_messages VALUES(4)')
            for query,name in queries:
                self.assertEqual(db.execute(query,{'threadId':4}).fetchall(),
                    [(3,4,200,0,1)] if name=='getLatestNonRecycledMessage' else [(2,)])

class NotificationUnreadSelectionTest(unittest.TestCase):
    def test_reading_old_message_keeps_newer_unread_candidate(self):
        source = (DAO / 'MessagesDao.kt').read_text()
        query = dict((name, sql) for sql, name in re.findall(r'@Query\("""(.*?)"""\)\s*fun (\w+)', source, re.S))['getLatestUnreadInboxMessage']
        with sqlite3.connect(':memory:') as db:
            db.executescript('CREATE TABLE messages(id INTEGER PRIMARY KEY,thread_id INTEGER,date INTEGER,read INTEGER,type INTEGER);CREATE TABLE recycle_bin_messages(id INTEGER PRIMARY KEY);')
            db.executemany('INSERT INTO messages VALUES(?,?,?,?,?)', [(10,4,100,0,1),(11,4,100,0,1),(12,4,200,0,1),(13,4,300,0,2)])
            db.execute('INSERT INTO recycle_bin_messages VALUES(12)')
            db.execute('UPDATE messages SET read=1 WHERE id=10')
            self.assertEqual(11, db.execute(query, {'threadId':4}).fetchone()[0])
            db.execute('UPDATE messages SET read=1 WHERE id=11')
            self.assertIsNone(db.execute(query, {'threadId':4}).fetchone())

if __name__ == '__main__':
    unittest.main()
