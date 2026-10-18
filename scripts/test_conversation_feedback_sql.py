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

if __name__ == '__main__':
    unittest.main()
