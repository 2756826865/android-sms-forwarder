"""Host regression checks for the production Room queries used by list refresh."""
import pathlib
import re
import sqlite3
import unittest

SOURCE = (pathlib.Path(__file__).resolve().parents[1] /
          'app/src/main/kotlin/org/fossify/messages/interfaces/MessagesDao.kt').read_text()


def query_for(method):
    return re.search(r'@Query\("([^"\n]+)"\)\s+fun ' + method + r'\(', SOURCE).group(1)


class BatteryQueryTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript('''
            CREATE TABLE messages(id INTEGER PRIMARY KEY, thread_id INTEGER,
                date INTEGER, is_scheduled INTEGER, body TEXT);
            CREATE INDEX messages_thread_date ON messages(thread_id, date);
            CREATE TABLE conversations(thread_id INTEGER PRIMARY KEY);
        ''')

    def tearDown(self):
        self.db.close()

    def add(self, ident, thread, date, scheduled=0):
        self.db.execute('INSERT INTO messages VALUES(?,?,?,?,?)',
                        (ident, thread, date, scheduled, 'message'))

    def test_missing_threads_only_and_latest_message(self):
        self.db.execute('INSERT INTO conversations VALUES(10)')
        self.add(1, 10, 200)
        self.add(2, 20, 100)
        self.add(3, 20, 200)
        self.add(4, 20, 300, 1)
        self.add(5, 0, 400)
        self.add(6, 30, 500, 1)
        self.add(7, 40, 600)
        result = self.db.execute(query_for('getLatestMessagesWithoutConversation')).fetchall()
        self.assertEqual({row[0] for row in result}, {3, 7})

    def test_equal_timestamps_have_one_deterministic_result(self):
        self.add(1, 20, 100)
        self.add(2, 20, 100)
        rows = self.db.execute(query_for('getLatestMessagesWithoutConversation')).fetchall()
        self.assertEqual([row[0] for row in rows], [2])

    def test_existing_thread_check_includes_scheduled_messages(self):
        self.add(1, 20, 100, 1)
        query = query_for('hasThreadMessages')
        self.assertEqual(self.db.execute(query, {'threadId': 20}).fetchone()[0], 1)
        self.assertEqual(self.db.execute(query, {'threadId': 30}).fetchone()[0], 0)

    def test_large_history_returns_only_missing_thread_summary(self):
        self.db.execute('INSERT INTO conversations VALUES(10)')
        self.db.executemany('INSERT INTO messages VALUES(?,?,?,?,?)',
                            ((i, 10, i, 0, 'x' * 100) for i in range(1, 10001)))
        self.add(10001, 20, 20000)
        rows = self.db.execute(query_for('getLatestMessagesWithoutConversation')).fetchall()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][0], 10001)


if __name__ == '__main__':
    unittest.main()
